package com.avn.stlviewer

import java.io.*
import java.net.URL

fun downloadFile(url: URL, outputFile: File) {
    url.openConnection()
    DataInputStream(url.openStream()).use { stream ->
        DataOutputStream(FileOutputStream(outputFile)).use { fos ->
            fos.write(stream.readBytes())
            fos.flush()
        }
    }
}
