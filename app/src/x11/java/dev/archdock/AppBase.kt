package dev.archdock

/**
 * Base Application when Termux:X11 (`lorie`) is embedded. Termux:X11's
 * activities cast `getApplication()` to LorieApp, so ArchDock's Application
 * has to extend it.
 */
open class AppBase : com.termux.x11.LorieApp()
