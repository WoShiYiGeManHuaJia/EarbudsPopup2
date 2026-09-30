package com.woshiyigemanhuajia.btpopup

import android.app.Application
import android.os.Build
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import com.woshiyigemanhuajia.btpopup.util.Prefs

class App : Application(), ImageLoaderFactory {

    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
    }

    /** 让 Coil 支持 GIF / 动图 */
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .components {
            if (Build.VERSION.SDK_INT >= 28) {
                add(ImageDecoderDecoder.Factory())
            } else {
                add(GifDecoder.Factory())
            }
        }
        .crossfade(true)
        .build()
}
