import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.example.bluefalconcomposemultiplatform.App
import com.example.bluefalconcomposemultiplatform.di.AppModule

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "Blue Falcon"
    ) {
        App(
            darkTheme = false,
            dynamicColor = false,
            appModule = AppModule()
        )
    }
}