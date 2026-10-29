//! The one call that goes through JNI directly instead of UniFFI: reqwest checks certificates
//! with the Android system verifier, which needs the app's `Context` before the first request.

use jni::EnvUnowned;
use jni::objects::{JClass, JObject};

#[unsafe(no_mangle)]
pub extern "system" fn Java_org_sonora_android_NativeInit_initVerifier<'caller>(
    mut unowned_env: EnvUnowned<'caller>,
    _class: JClass<'caller>,
    context: JObject<'caller>,
) {
    let outcome = unowned_env.with_env(|env| -> Result<(), jni::errors::Error> {
        rustls_platform_verifier::android::init_with_env(env, context)
    });
    outcome.resolve::<jni::errors::ThrowRuntimeExAndDefault>()
}
