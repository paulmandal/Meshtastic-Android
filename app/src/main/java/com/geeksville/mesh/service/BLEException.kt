package com.geeksville.mesh.service

import java.io.IOException
import java.util.*

open class BLEException(msg: String) : IOException(msg)

open class BLECharacteristicNotFoundException(uuid: UUID) : BLEException("Can't get characteristic $uuid")