package com.anonrode.downloader.data.net

internal class OriginCooldownException(val originUrl: String) :
    java.io.IOException("Origin temporarily cooling down")
