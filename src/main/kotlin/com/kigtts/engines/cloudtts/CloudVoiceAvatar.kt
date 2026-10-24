package com.kigtts.engines.cloudtts

import android.graphics.BitmapFactory
import com.kigtts.plugin.api.PluginStorage
import java.util.UUID

internal object CloudVoiceAvatar {
    fun copy(storage: PluginStorage, sourcePath: String): String {
        val source = storage.file(sourcePath)
        require(source.isFile && source.length() in 1..5_242_880) { "请选择不超过 5 MB 的图片" }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.absolutePath, bounds)
        require(bounds.outWidth in 1..8192 && bounds.outHeight in 1..8192) { "图片无法读取或尺寸过大" }
        storage.directory("resources/avatars")
        val path = "resources/avatars/${UUID.randomUUID()}.image"
        source.copyTo(storage.file(path))
        return path
    }
}
