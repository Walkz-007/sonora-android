//! The bridge between Sonora's streaming engine (the vendored `music` crate) and the Android app.
//!
//! Everything here is exported to Kotlin through UniFFI. The engine runs on a tokio runtime this
//! crate owns; Kotlin calls the async functions as `suspend` functions and gets playback events
//! through a callback interface.

use std::collections::HashMap;
use std::path::PathBuf;
use std::sync::{Arc, Mutex};
use std::time::Duration;

use music::equalizer::{Curve, EqualizerWriter, Preamp};
use music::{
    Analyzer, Meter, MusicApi, MusicProvider, PlaybackConfig, PlaybackEvent, Player,
    Quality, Shape, SignIn, SignInPrompt, Transcode,
};
use tokio::runtime::Runtime;

mod convert;
#[cfg(target_os = "android")]
mod jni_init;

pub use convert::*;

uniffi::setup_scaffolding!();

/// The music sources the Android app offers.
#[derive(uniffi::Enum, Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum Source {
    Local,
    Subsonic,
    YouTube,
    Spotify,
}

impl Source {
    pub const ALL: [Source; 4] = [Source::Local, Source::Subsonic, Source::YouTube, Source::Spotify];
}

#[derive(Debug, uniffi::Error)]
#[uniffi(flat_error)]
pub enum CoreError {
    Failed { message: String },
    NotSignedIn,
}

impl std::fmt::Display for CoreError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            CoreError::Failed { message } => f.write_str(message),
            CoreError::NotSignedIn => f.write_str("this source is not signed in"),
        }
    }
}

impl std::error::Error for CoreError {}

impl From<anyhow::Error> for CoreError {
    fn from(error: anyhow::Error) -> Self {
        CoreError::Failed {
            message: format!("{error:#}"),
        }
    }
}

type Res<T> = Result<T, CoreError>;

/// A signed-in account, as the accounts screen shows it.
#[derive(uniffi::Record, Clone, Debug)]
pub struct Account {
    pub source: Source,
    pub name: String,
    pub avatar: Option<String>,
    pub radio: bool,
}

/// What the engine reports while playing, tagged with the source whose engine sent it.
#[derive(uniffi::Enum, Clone, Debug)]
pub enum PlayerEvent {
    Loading { id: Option<String> },
    Playing { id: Option<String>, position_ms: u64 },
    Paused { id: Option<String>, position_ms: u64 },
    Position { id: Option<String>, position_ms: u64 },
    Seeked { id: Option<String>, position_ms: u64 },
    Length { id: Option<String>, duration_ms: u64 },
    Ended { id: Option<String> },
    Unavailable { id: Option<String> },
    Throttled { id: Option<String> },
    Refused,
    Format { id: String, description: String },
}

/// Receives playback events. Called from engine threads, never the main thread.
#[uniffi::export(with_foreign)]
pub trait PlayerListener: Send + Sync {
    fn on_event(&self, source: Source, event: PlayerEvent);
}

/// What a sign-in needs the app to do.
#[derive(uniffi::Enum, Clone, Debug)]
pub enum SignInStep {
    /// Open this address in a browser. Spotify's login redirects back to the app afterwards.
    OpenUrl { url: String },
    /// Show this code and send the user to `url` to enter it.
    ShowCode { code: String, url: String },
    /// Pick one account; answer with `provide_sign_in_input` and the chosen `id`.
    ChooseAccount { choices: Vec<AccountChoice> },
}

#[derive(uniffi::Record, Clone, Debug)]
pub struct AccountChoice {
    pub id: String,
    pub name: String,
    pub detail: Option<String>,
}

#[uniffi::export(with_foreign)]
pub trait SignInListener: Send + Sync {
    fn on_step(&self, step: SignInStep);
}

/// The browser sign-in YouTube Music uses: load `url`, and once the cookies for `domain` carry
/// one of `proof`, hand the whole cookie header to `sign_in_youtube_cookies`.
#[derive(uniffi::Record, Clone, Debug)]
pub struct WebLogin {
    pub url: String,
    pub domain: String,
    pub proof: Vec<String>,
    pub user_agent: Option<String>,
}

struct Session {
    api: Arc<dyn MusicApi>,
    player: Box<dyn Player>,
    shape: Shape,
    account: Account,
}

#[derive(uniffi::Object)]
pub struct Core {
    runtime: Runtime,
    files_dir: PathBuf,
    providers: HashMap<Source, Arc<dyn MusicProvider>>,
    sessions: Mutex<HashMap<Source, Arc<Session>>>,
    listener: Arc<Mutex<Option<Arc<dyn PlayerListener>>>>,
    inputs: Mutex<HashMap<Source, tokio::sync::mpsc::UnboundedSender<String>>>,
    /// Kept so the shared equalizer the engines read stays alive. Flat and off.
    _equalizer: Mutex<EqualizerWriter>,
    config: PlaybackConfig,
}

const LOCAL_FOLDERS: &str = "local-folders.json";

#[uniffi::export]
impl Core {
    /// Starts the engine. `files_dir` and `cache_dir` are the app's private folders; Sonora keeps
    /// its sign-ins, databases and caches under them.
    #[uniffi::constructor]
    pub fn new(files_dir: String, cache_dir: String) -> Res<Arc<Self>> {
        init_logging();
        let files_dir = PathBuf::from(files_dir);
        let cache_dir = PathBuf::from(cache_dir);
        // Sonora finds its folders through the XDG variables, which Android does not set.
        // SAFETY: called once at startup, before this library starts any thread of its own.
        unsafe {
            std::env::set_var("HOME", &files_dir);
            std::env::set_var("XDG_DATA_HOME", files_dir.join("data"));
            std::env::set_var("XDG_CONFIG_HOME", files_dir.join("config"));
            std::env::set_var("XDG_CACHE_HOME", &cache_dir);
            #[cfg(target_os = "android")]
            if std::env::var_os("SSL_CERT_DIR").is_none() {
                std::env::set_var("SSL_CERT_DIR", "/system/etc/security/cacerts");
            }
        }
        for dir in ["data", "config"] {
            std::fs::create_dir_all(files_dir.join(dir)).ok();
        }
        std::fs::create_dir_all(&cache_dir).ok();

        // Two rustls backends are compiled in, so one has to be picked, as Sonora itself does.
        rustls::crypto::ring::default_provider()
            .install_default()
            .ok();

        let runtime = tokio::runtime::Builder::new_multi_thread()
            .worker_threads(4)
            .thread_name("sonora-core")
            .enable_all()
            .build()
            .map_err(|error| CoreError::Failed {
                message: format!("cannot start the runtime: {error}"),
            })?;

        let database = storage::Database::standard();
        let mut providers: HashMap<Source, Arc<dyn MusicProvider>> = HashMap::new();
        providers.insert(
            Source::Local,
            Arc::new(music::local::LocalProvider::new(
                cache_dir.join("sonora"),
                database,
                storage::Cache::standard(),
            )),
        );
        providers.insert(
            Source::Subsonic,
            Arc::new(music::subsonic::SubsonicProvider::new()),
        );
        providers.insert(
            Source::YouTube,
            Arc::new(music::youtube::YouTubeProvider::new()),
        );
        providers.insert(
            Source::Spotify,
            Arc::new(music::spotify::SpotifyProvider::from_env()),
        );

        let flat = Curve::new(Preamp::Auto, Vec::new()).map_err(|error| CoreError::Failed {
            message: format!("cannot build the equalizer: {error}"),
        })?;
        let equalizer = EqualizerWriter::new(false, flat);
        let config = PlaybackConfig {
            normalisation: true,
            gapless: true,
            position_interval: Duration::from_millis(500),
            gain: 1.0,
            equalizer: equalizer.reader(),
            transcode: Transcode::default(),
            quality: Quality::High,
            meter: Meter::default(),
            analyzer: Analyzer::default(),
            vault: None,
        };

        Ok(Arc::new(Self {
            runtime,
            files_dir,
            providers,
            sessions: Mutex::new(HashMap::new()),
            listener: Arc::new(Mutex::new(None)),
            inputs: Mutex::new(HashMap::new()),
            _equalizer: Mutex::new(equalizer),
            config,
        }))
    }

    pub fn set_listener(&self, listener: Arc<dyn PlayerListener>) {
        *self.listener.lock().unwrap() = Some(listener);
    }

    /// Restores every account a previous launch signed in, and answers with those that came
    /// back. A source that fails to restore is left signed out.
    pub async fn restore(self: Arc<Self>) -> Vec<Account> {
        let core = self.clone();
        let task = self.runtime.spawn(async move {
            let mut restored = Vec::new();
            for source in Source::ALL {
                let result = if source == Source::Local {
                    match core.local_folders() {
                        Some(folders) if !folders.is_empty() => core
                            .sign_in_with(source, SignIn::Path(folders), None, None)
                            .await
                            .map(Some),
                        _ => Ok(None),
                    }
                } else {
                    let provider = core.providers[&source].clone();
                    if !provider.stored() {
                        continue;
                    }
                    match provider.restore().await {
                        Ok(Some(session)) => Ok(Some(core.install(source, session))),
                        Ok(None) => Ok(None),
                        Err(error) => Err(CoreError::from(error)),
                    }
                };
                match result {
                    Ok(Some(account)) => restored.push(account),
                    Ok(None) => {}
                    Err(error) => log::warn!("restore {source:?}: {error}"),
                }
            }
            restored
        });
        task.await.unwrap_or_default()
    }

    pub fn accounts(&self) -> Vec<Account> {
        let sessions = self.sessions.lock().unwrap();
        Source::ALL
            .iter()
            .filter_map(|source| sessions.get(source).map(|s| s.account.clone()))
            .collect()
    }

    /// Scans these folders for music and signs local files in.
    pub async fn sign_in_local(self: Arc<Self>, folders: Vec<String>) -> Res<Account> {
        let paths: Vec<PathBuf> = folders.iter().map(PathBuf::from).collect();
        let core = self.clone();
        let account = self
            .run(async move {
                core.sign_in_with(Source::Local, SignIn::Path(paths), None, None)
                    .await
            })
            .await?;
        let body = serde_json::to_vec(&folders).unwrap_or_default();
        std::fs::write(self.files_dir.join(LOCAL_FOLDERS), body).ok();
        Ok(account)
    }

    pub async fn sign_in_subsonic(
        self: Arc<Self>,
        server: String,
        username: String,
        password: String,
    ) -> Res<Account> {
        let core = self.clone();
        self.run(async move {
            core.sign_in_with(
                Source::Subsonic,
                SignIn::Credentials {
                    server,
                    username,
                    password,
                },
                None,
                None,
            )
            .await
        })
        .await
    }

    /// YouTube Music without an account: search and play, no library.
    pub async fn sign_in_youtube_guest(self: Arc<Self>) -> Res<Account> {
        let core = self.clone();
        self.run(async move {
            core.sign_in_with(Source::YouTube, SignIn::Anonymous, None, None)
                .await
        })
        .await
    }

    /// YouTube Music with the cookie header from a signed-in browser session.
    pub async fn sign_in_youtube_cookies(
        self: Arc<Self>,
        cookies: String,
        listener: Arc<dyn SignInListener>,
    ) -> Res<Account> {
        let core = self.clone();
        self.run(async move {
            core.sign_in_with(Source::YouTube, SignIn::Secret, Some(cookies), Some(listener))
                .await
        })
        .await
    }

    /// How the app should run the YouTube Music browser sign-in.
    pub fn youtube_web_login(&self) -> Option<WebLogin> {
        let web = self.providers[&Source::YouTube].web_sign_in()?;
        Some(WebLogin {
            url: web.url.to_owned(),
            domain: web.domain.to_owned(),
            proof: web.proof.iter().map(|p| (*p).to_owned()).collect(),
            user_agent: web.agent.map(str::to_owned),
        })
    }

    /// Spotify's browser login. `listener` gets the address to open; the login redirects back to
    /// `http://127.0.0.1:8989/login`, which this library listens on. Needs Spotify Premium.
    pub async fn sign_in_spotify(
        self: Arc<Self>,
        listener: Arc<dyn SignInListener>,
    ) -> Res<Account> {
        let core = self.clone();
        self.run(async move {
            core.sign_in_with(Source::Spotify, SignIn::Default, None, Some(listener))
                .await
        })
        .await
    }

    /// Answers a sign-in step that asked for input, such as an account choice.
    pub fn provide_sign_in_input(&self, source: Source, text: String) {
        if let Some(sender) = self.inputs.lock().unwrap().get(&source) {
            sender.send(text).ok();
        }
    }

    /// Gives up on a sign-in that is waiting on the browser.
    pub fn cancel_sign_in(&self, source: Source) {
        self.inputs.lock().unwrap().remove(&source);
        self.providers[&source].abandon();
    }

    pub fn sign_out(&self, source: Source) {
        if let Some(session) = self.sessions.lock().unwrap().remove(&source) {
            let _guard = self.runtime.enter();
            session.player.pause();
        }
        self.providers[&source].sign_out();
        if source == Source::Local {
            std::fs::remove_file(self.files_dir.join(LOCAL_FOLDERS)).ok();
        }
    }

    pub async fn home(self: Arc<Self>, source: Source) -> Res<Vec<Shelf>> {
        let api = self.api(source)?;
        self.run(async move {
            let feed = api.home().await?;
            let mut shelves = Vec::new();
            if let Some(picks) = feed.quick_picks.filter(|p| !p.is_empty()) {
                shelves.push(Shelf::of_tracks("Quick picks", &picks));
            }
            if !feed.listen_again.is_empty() {
                shelves.push(Shelf::of_items("Listen again", &feed.listen_again));
            }
            for section in &feed.sections {
                let shelf = Shelf::of_items(&section.title, &section.items);
                if !shelf.is_empty() {
                    shelves.push(shelf);
                }
            }
            if shelves.is_empty() {
                // A provider without a home feed still has a library to show.
                let playlists = api.playlists().await.unwrap_or_default();
                if !playlists.is_empty() {
                    shelves.push(Shelf {
                        title: "Your playlists".into(),
                        playlists: playlists.iter().map(PlaylistItem::from).collect(),
                        ..Shelf::default()
                    });
                }
                let albums = api.all_albums().await.unwrap_or_default();
                let albums = if albums.is_empty() {
                    api.saved_albums().await.unwrap_or_default()
                } else {
                    albums
                };
                if !albums.is_empty() {
                    shelves.push(Shelf {
                        title: "Albums".into(),
                        albums: albums.iter().take(40).map(AlbumItem::from).collect(),
                        ..Shelf::default()
                    });
                }
            }
            Ok(shelves)
        })
        .await
    }

    pub async fn search(self: Arc<Self>, source: Source, query: String) -> Res<SearchResults> {
        let api = self.api(source)?;
        self.run(async move {
            let (tracks, albums, playlists) = tokio::join!(
                api.search(&query),
                api.search_albums(&query),
                api.search_playlists(&query)
            );
            Ok(SearchResults {
                tracks: tracks?.iter().map(TrackItem::from).collect(),
                albums: albums
                    .unwrap_or_default()
                    .iter()
                    .map(AlbumItem::from)
                    .collect(),
                playlists: playlists
                    .unwrap_or_default()
                    .iter()
                    .map(PlaylistItem::from)
                    .collect(),
            })
        })
        .await
    }

    pub async fn library(self: Arc<Self>, source: Source) -> Res<LibraryContents> {
        let (api, shape) = {
            let session = self.session(source)?;
            (session.api.clone(), session.shape)
        };
        self.run(async move {
            let catalog = shape.is_catalog();
            let (playlists, albums, artists, tracks) = if catalog {
                tokio::join!(
                    api.playlists(),
                    api.all_albums(),
                    api.all_artists(),
                    api.all_tracks()
                )
            } else {
                tokio::join!(
                    api.playlists(),
                    api.saved_albums(),
                    api.saved_artists(),
                    api.saved_tracks()
                )
            };
            Ok(LibraryContents {
                playlists: playlists
                    .unwrap_or_default()
                    .iter()
                    .map(PlaylistItem::from)
                    .collect(),
                albums: albums
                    .unwrap_or_default()
                    .iter()
                    .map(AlbumItem::from)
                    .collect(),
                artists: artists
                    .unwrap_or_default()
                    .iter()
                    .map(ArtistItem::from)
                    .collect(),
                tracks: tracks
                    .unwrap_or_default()
                    .iter()
                    .map(TrackItem::from)
                    .collect(),
            })
        })
        .await
    }

    pub async fn album(self: Arc<Self>, source: Source, id: String) -> Res<CollectionPage> {
        let api = self.api(source)?;
        self.run(async move {
            let detail = api.album(&id).await?;
            let tracks = if detail.tracks.is_empty() {
                api.album_tracks(&id).await?
            } else {
                detail.tracks
            };
            let album = detail.album;
            let subtitle = match album.year {
                year if year > 0 => format!("{} · {year}", album.artists),
                _ => album.artists.clone(),
            };
            Ok(CollectionPage {
                id: album.id.clone(),
                title: album.name.clone(),
                subtitle,
                cover: album.cover_large.clone().or(album.cover.clone()),
                tracks: tracks.iter().map(TrackItem::from).collect(),
                continuation: None,
            })
        })
        .await
    }

    pub async fn playlist(self: Arc<Self>, source: Source, id: String) -> Res<CollectionPage> {
        let api = self.api(source)?;
        self.run(async move {
            let detail = api.playlist(&id).await?;
            let tracks = if detail.tracks.is_empty() {
                api.playlist_tracks(&id).await.unwrap_or_default()
            } else {
                detail.tracks
            };
            let playlist = detail.playlist;
            Ok(CollectionPage {
                id: playlist.id.clone(),
                title: playlist.name.clone(),
                subtitle: playlist.owner.clone(),
                cover: playlist.cover.clone(),
                tracks: tracks.iter().map(TrackItem::from).collect(),
                continuation: detail.continuation,
            })
        })
        .await
    }

    /// The next stretch of a long playlist, from the continuation `playlist` answered with.
    pub async fn playlist_more(
        self: Arc<Self>,
        source: Source,
        continuation: String,
    ) -> Res<TrackPage> {
        let api = self.api(source)?;
        self.run(async move {
            let (tracks, next) = api.playlist_continuation(&continuation).await?;
            Ok(TrackPage {
                tracks: tracks.iter().map(TrackItem::from).collect(),
                continuation: next,
            })
        })
        .await
    }

    pub async fn artist(self: Arc<Self>, source: Source, id: String) -> Res<ArtistPage> {
        let api = self.api(source)?;
        self.run(async move {
            let artist = api.artist(&id).await?;
            let mut albums = artist.albums;
            let mut top = artist.top_tracks;
            if let Ok(more) = api.artist_catalogue(&id, &top).await {
                if !more.albums.is_empty() {
                    albums = more.albums;
                }
                if !more.top_tracks.is_empty() {
                    top = more.top_tracks;
                }
            }
            Ok(ArtistPage {
                name: artist.name,
                cover: artist.cover_large,
                top_tracks: top.iter().map(TrackItem::from).collect(),
                albums: albums.iter().map(AlbumItem::from).collect(),
            })
        })
        .await
    }

    /// A station seeded by a track, for the queue to continue with.
    pub async fn radio(self: Arc<Self>, source: Source, track_id: String) -> Res<Vec<TrackItem>> {
        let api = self.api(source)?;
        self.run(async move {
            let (tracks, _) = api.track_radio(&track_id, None).await?;
            Ok(tracks.iter().map(TrackItem::from).collect())
        })
        .await
    }

    pub async fn set_saved(self: Arc<Self>, source: Source, track_id: String, saved: bool) -> Res<()> {
        let api = self.api(source)?;
        self.run(async move {
            api.set_track_saved(&track_id, saved).await?;
            Ok(())
        })
        .await
    }

    // Playback. Every call is fire-and-forget; what happens arrives as a `PlayerEvent`.

    /// Plays a track from `position_ms`. `seamless` marks a segue to the next queue item, which a
    /// gapless engine joins without a gap. Pauses every other source first.
    pub fn load(&self, source: Source, track_id: String, position_ms: u64, seamless: bool) -> Res<()> {
        let _guard = self.runtime.enter();
        let sessions = self.sessions.lock().unwrap();
        for (other, session) in sessions.iter() {
            if *other != source {
                session.player.pause();
            }
        }
        let session = sessions.get(&source).ok_or(CoreError::NotSignedIn)?;
        session
            .player
            .load(&track_id, Duration::from_millis(position_ms), seamless)?;
        Ok(())
    }

    /// Fetches a track ahead of time; `segue` marks the next queue item.
    pub fn preload(&self, source: Source, track_id: String, segue: bool) -> Res<()> {
        let _guard = self.runtime.enter();
        let session = self.session(source)?;
        session.player.preload(&track_id, segue)?;
        Ok(())
    }

    pub fn play(&self, source: Source) {
        let _guard = self.runtime.enter();
        if let Ok(session) = self.session(source) {
            session.player.play();
        }
    }

    pub fn pause(&self, source: Source) {
        let _guard = self.runtime.enter();
        if let Ok(session) = self.session(source) {
            session.player.pause();
        }
    }

    pub fn seek(&self, source: Source, position_ms: u64) {
        let _guard = self.runtime.enter();
        if let Ok(session) = self.session(source) {
            session.player.seek(Duration::from_millis(position_ms));
        }
    }

    /// Linear gain from 0 to 1, on top of the system volume.
    pub fn set_gain(&self, gain: f32) {
        let _guard = self.runtime.enter();
        for session in self.sessions.lock().unwrap().values() {
            session.player.set_gain(gain.clamp(0.0, 1.0));
        }
    }
}

impl Core {
    fn session(&self, source: Source) -> Res<Arc<Session>> {
        self.sessions
            .lock()
            .unwrap()
            .get(&source)
            .cloned()
            .ok_or(CoreError::NotSignedIn)
    }

    fn api(&self, source: Source) -> Res<Arc<dyn MusicApi>> {
        Ok(self.session(source)?.api.clone())
    }

    /// Runs a future on the engine's runtime and waits for it from whatever executor called.
    async fn run<T, F>(&self, future: F) -> Res<T>
    where
        T: Send + 'static,
        F: std::future::Future<Output = Res<T>> + Send + 'static,
    {
        match self.runtime.spawn(future).await {
            Ok(result) => result,
            Err(error) => Err(CoreError::Failed {
                message: format!("the task failed: {error}"),
            }),
        }
    }

    fn local_folders(&self) -> Option<Vec<PathBuf>> {
        let body = std::fs::read(self.files_dir.join(LOCAL_FOLDERS)).ok()?;
        let folders: Vec<String> = serde_json::from_slice(&body).ok()?;
        Some(folders.into_iter().map(PathBuf::from).collect())
    }

    async fn sign_in_with(
        self: &Arc<Self>,
        source: Source,
        method: SignIn,
        first_input: Option<String>,
        listener: Option<Arc<dyn SignInListener>>,
    ) -> Res<Account> {
        let provider = self.providers[&source].clone();
        let (sender, receiver) = tokio::sync::mpsc::unbounded_channel();
        if let Some(text) = first_input {
            sender.send(text).ok();
        }
        self.inputs.lock().unwrap().insert(source, sender);
        let prompt: music::PromptSink = Arc::new(move |prompt: SignInPrompt| {
            let Some(listener) = &listener else { return };
            let step = match prompt {
                SignInPrompt::Url(url) => SignInStep::OpenUrl { url },
                SignInPrompt::Code { code, url } => SignInStep::ShowCode { code, url },
                SignInPrompt::Accounts(choices) => SignInStep::ChooseAccount {
                    choices: choices
                        .into_iter()
                        .map(|c| AccountChoice {
                            id: c.id,
                            name: c.name,
                            detail: c.detail,
                        })
                        .collect(),
                },
                // The secret was queued before the sign-in started.
                SignInPrompt::Secret => return,
            };
            listener.on_step(step);
        });
        let result = provider.sign_in(method, prompt, receiver).await;
        self.inputs.lock().unwrap().remove(&source);
        let session = result?;
        Ok(self.install(source, session))
    }

    /// Starts an engine for a fresh session and keeps it, replacing any earlier one.
    fn install(self: &Arc<Self>, source: Source, session: music::ProviderSession) -> Account {
        let _guard = self.runtime.enter();
        let (player, mut events) = session.playback.start(self.config.clone());
        let listener = self.listener.clone();
        self.runtime.spawn(async move {
            while let Some(event) = events.next().await {
                let Some(event) = convert_event(event) else {
                    continue;
                };
                let current = listener.lock().unwrap().clone();
                if let Some(listener) = current {
                    listener.on_event(source, event);
                }
            }
        });
        let account = Account {
            source,
            name: session.profile.display_name.clone(),
            avatar: session.profile.avatar.clone(),
            radio: session.capabilities.radio,
        };
        let installed = Arc::new(Session {
            api: session.api,
            player,
            shape: session.shape,
            account: account.clone(),
        });
        if let Some(old) = self.sessions.lock().unwrap().insert(source, installed) {
            old.player.pause();
        }
        account
    }
}

fn convert_event(event: PlaybackEvent) -> Option<PlayerEvent> {
    let ms = |d: Duration| d.as_millis() as u64;
    Some(match event {
        PlaybackEvent::Loading { id, .. } => PlayerEvent::Loading { id },
        PlaybackEvent::Playing { id, at } => PlayerEvent::Playing {
            id,
            position_ms: ms(at),
        },
        PlaybackEvent::Paused { id, at } => PlayerEvent::Paused {
            id,
            position_ms: ms(at),
        },
        PlaybackEvent::Position { id, at } => PlayerEvent::Position {
            id,
            position_ms: ms(at),
        },
        PlaybackEvent::Seeked { id, at } => PlayerEvent::Seeked {
            id,
            position_ms: ms(at),
        },
        PlaybackEvent::Length { id, duration } => PlayerEvent::Length {
            id,
            duration_ms: ms(duration),
        },
        PlaybackEvent::Ended { id } => PlayerEvent::Ended { id },
        PlaybackEvent::Unavailable { id } => PlayerEvent::Unavailable { id },
        PlaybackEvent::Throttled { id } => PlayerEvent::Throttled { id },
        PlaybackEvent::Refused | PlaybackEvent::Gated => PlayerEvent::Refused,
        PlaybackEvent::Signal { id, track } => PlayerEvent::Format {
            id,
            description: describe_signal(&track),
        },
        PlaybackEvent::Downloaded { .. } | PlaybackEvent::OutputChanged => return None,
    })
}

fn describe_signal(signal: &music::TrackSignal) -> String {
    use music::Encoding;
    let codec = match signal.encoding {
        Some(Encoding::Flac { depth, .. }) => format!("FLAC {depth}-bit"),
        Some(Encoding::Alac { depth }) => format!("ALAC {depth}-bit"),
        Some(Encoding::Pcm { depth }) => format!("PCM {depth}-bit"),
        Some(Encoding::Aac { kbps }) => format!("AAC {kbps} kbps"),
        Some(Encoding::Vorbis { kbps }) => format!("Vorbis {kbps} kbps"),
        Some(Encoding::Mp3 { kbps }) => format!("MP3 {kbps} kbps"),
        Some(Encoding::Opus { kbps }) => format!("Opus {kbps} kbps"),
        Some(Encoding::Other) | None => String::new(),
    };
    let rate = format!("{:.1} kHz", signal.rate as f32 / 1000.0);
    if codec.is_empty() {
        rate
    } else {
        format!("{codec} · {rate}")
    }
}

/// Exposed so the Kotlin home screen can tell an empty shelf from a loaded one.
impl Shelf {
    fn is_empty(&self) -> bool {
        self.tracks.is_empty()
            && self.albums.is_empty()
            && self.playlists.is_empty()
            && self.artists.is_empty()
    }
}


fn init_logging() {
    #[cfg(target_os = "android")]
    android_logger::init_once(
        android_logger::Config::default()
            .with_max_level(log::LevelFilter::Info)
            .with_tag("sonora"),
    );
    #[cfg(not(target_os = "android"))]
    {
        let _ = env_logger::try_init();
    }
}
