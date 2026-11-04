package org.mewx.wenku8_flutter

/** JNI entry point used by the upstream, unchanged bounded libmobi exporter. */
internal object MobiConverter {
    init { System.loadLibrary("wenku_mobi") }
    external fun exportParts(input: String, output: String): Int
}
