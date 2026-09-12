package dev.ynagai.agui.sample

import androidx.compose.runtime.remember
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application

/**
 * The desktop entry point: `./gradlew :agui-sample:run`.
 *
 * An object with a `@JvmStatic main` rather than a top-level `fun main`, because
 * `compose.desktop { application { mainClass } }` names a class and a top-level function's
 * synthetic holder (`Main_jvmKt`) is a name derived from the file rather than chosen.
 *
 * The `SampleChat` is remembered at the top of the composition and disposed by [SampleApp], so its
 * agent's lifetime is the window's.
 */
public object SampleMain {
    @JvmStatic
    public fun main(args: Array<String>) {
        application {
            Window(onCloseRequest = ::exitApplication, title = "agui-compose sample") {
                SampleApp(chat = remember { SampleChat() })
            }
        }
    }
}
