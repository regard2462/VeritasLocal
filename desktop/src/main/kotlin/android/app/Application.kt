package android.app

import android.content.Context
import java.io.File

/** Desktop-Ersatz fuer android.app.Application: der Datenordner der App. */
open class Application(dataDir: File) : Context(dataDir)
