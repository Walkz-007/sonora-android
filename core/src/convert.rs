//! Plain records Kotlin sees, made from Sonora's models.

use music::{Album, GenreItem, Playlist, SavedArtist, Track};

#[derive(uniffi::Record, Clone, Debug, Default)]
pub struct TrackItem {
    pub id: String,
    pub title: String,
    pub artists: String,
    /// The first artist that has a page of its own.
    pub artist_id: Option<String>,
    pub album: String,
    pub album_id: Option<String>,
    pub cover: Option<String>,
    pub duration_ms: u64,
    pub playable: bool,
    pub explicit: bool,
}

#[derive(uniffi::Record, Clone, Debug, Default)]
pub struct AlbumItem {
    pub id: String,
    pub title: String,
    pub artists: String,
    pub cover: Option<String>,
    pub year: i32,
}

#[derive(uniffi::Record, Clone, Debug, Default)]
pub struct PlaylistItem {
    pub id: String,
    pub name: String,
    pub owner: String,
    pub cover: Option<String>,
    pub track_count: u32,
}

#[derive(uniffi::Record, Clone, Debug, Default)]
pub struct ArtistItem {
    pub id: String,
    pub name: String,
    pub cover: Option<String>,
}

/// One row of the home screen.
#[derive(uniffi::Record, Clone, Debug, Default)]
pub struct Shelf {
    pub title: String,
    pub tracks: Vec<TrackItem>,
    pub albums: Vec<AlbumItem>,
    pub playlists: Vec<PlaylistItem>,
    pub artists: Vec<ArtistItem>,
}

#[derive(uniffi::Record, Clone, Debug, Default)]
pub struct SearchResults {
    pub tracks: Vec<TrackItem>,
    pub albums: Vec<AlbumItem>,
    pub playlists: Vec<PlaylistItem>,
}

#[derive(uniffi::Record, Clone, Debug, Default)]
pub struct LibraryContents {
    pub playlists: Vec<PlaylistItem>,
    pub albums: Vec<AlbumItem>,
    pub artists: Vec<ArtistItem>,
    pub tracks: Vec<TrackItem>,
}

/// An album or a playlist page.
#[derive(uniffi::Record, Clone, Debug, Default)]
pub struct CollectionPage {
    pub id: String,
    pub title: String,
    pub subtitle: String,
    pub cover: Option<String>,
    pub tracks: Vec<TrackItem>,
    pub continuation: Option<String>,
}

#[derive(uniffi::Record, Clone, Debug, Default)]
pub struct TrackPage {
    pub tracks: Vec<TrackItem>,
    pub continuation: Option<String>,
}

#[derive(uniffi::Record, Clone, Debug, Default)]
pub struct ArtistPage {
    pub name: String,
    pub cover: Option<String>,
    pub top_tracks: Vec<TrackItem>,
    pub albums: Vec<AlbumItem>,
}

impl From<&Track> for TrackItem {
    fn from(track: &Track) -> Self {
        Self {
            id: track.id.clone().unwrap_or_default(),
            title: track.name.clone(),
            artists: track.artists.clone(),
            artist_id: track.artist_refs.iter().find_map(|a| a.id.clone()),
            album: track.album.clone(),
            album_id: track.album_id.clone(),
            cover: track.cover.clone(),
            duration_ms: track.duration.as_millis() as u64,
            playable: track.playable && track.id.is_some(),
            explicit: track.explicit,
        }
    }
}

impl From<&Album> for AlbumItem {
    fn from(album: &Album) -> Self {
        Self {
            id: album.id.clone(),
            title: album.name.clone(),
            artists: album.artists.clone(),
            cover: album.cover.clone().or_else(|| album.cover_large.clone()),
            year: album.year,
        }
    }
}

impl From<&Playlist> for PlaylistItem {
    fn from(playlist: &Playlist) -> Self {
        Self {
            id: playlist.id.clone(),
            name: playlist.name.clone(),
            owner: playlist.owner.clone(),
            cover: playlist.cover.clone(),
            track_count: playlist.track_count,
        }
    }
}

impl From<&SavedArtist> for ArtistItem {
    fn from(artist: &SavedArtist) -> Self {
        Self {
            id: artist.id.clone(),
            name: artist.name.clone(),
            cover: artist.cover.clone(),
        }
    }
}

impl Shelf {
    pub(crate) fn of_tracks(title: &str, tracks: &[Track]) -> Self {
        Self {
            title: title.to_owned(),
            tracks: tracks.iter().map(TrackItem::from).collect(),
            ..Self::default()
        }
    }

    /// A browse shelf mixes kinds; each goes into its own list, and the app draws them in turn.
    pub(crate) fn of_items(title: &str, items: &[GenreItem]) -> Self {
        let mut shelf = Self {
            title: title.to_owned(),
            ..Self::default()
        };
        for item in items {
            match item {
                GenreItem::Track(track) => shelf.tracks.push(track.into()),
                GenreItem::Album(album) => shelf.albums.push(album.into()),
                GenreItem::Playlist(playlist) => shelf.playlists.push(playlist.into()),
                GenreItem::Artist(artist) => shelf.artists.push(artist.into()),
                GenreItem::Genre(_) => {}
            }
        }
        shelf
    }
}
