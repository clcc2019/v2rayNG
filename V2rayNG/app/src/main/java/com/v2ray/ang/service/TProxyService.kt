package com.v2ray.ang.service

/**
 * JNI shim for legacy native bindings.
 *
 * The hev-socks5-tunnel native library registers its JNI methods against
 * com.v2ray.ang.service.TProxyService. Newer app code lives under
 * com.xray.ang.service, but the native layer still looks for this class.
 *
 * Keep this class and method signatures in sync with the native library.
 */
class TProxyService {
    companion object {
        @JvmStatic
        @Suppress("FunctionName")
        external fun TProxyStartService(configPath: String, fd: Int)

        @JvmStatic
        @Suppress("FunctionName")
        external fun TProxyStopService()

        @JvmStatic
        @Suppress("FunctionName")
        external fun TProxyGetStats(): LongArray?
    }
}
