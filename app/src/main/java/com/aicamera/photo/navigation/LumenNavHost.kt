package com.aicamera.photo.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.aicamera.photo.features.academy.AcademyScreen
import com.aicamera.photo.features.academy.LessonScreen
import com.aicamera.photo.features.camera.CameraRoute
import com.aicamera.photo.features.filmlab.FilmLabScreen
import com.aicamera.photo.features.library.CompareScreen
import com.aicamera.photo.features.library.LibraryScreen
import com.aicamera.photo.features.library.PhotoDetailScreen
import com.aicamera.photo.features.library.ProgressScreen
import com.aicamera.photo.features.review.ReviewScreen
import com.aicamera.photo.features.settings.DiagnosticsScreen
import com.aicamera.photo.features.settings.ModelsScreen
import com.aicamera.photo.features.settings.SettingsScreen
import com.aicamera.photo.features.settings.StorageScreen

object Routes {
    const val CAMERA = "camera?lesson={lesson}&strategy={strategy}"
    fun camera(lessonId: String? = null, strategy: String? = null) =
        "camera?lesson=${lessonId.orEmpty()}&strategy=${strategy.orEmpty()}"

    fun review(photoId: String) = "review/$photoId"
    fun filmLab(photoId: String, editId: String? = null) = "filmlab/$photoId?edit=${editId.orEmpty()}"
    const val LIBRARY = "library"
    fun photo(photoId: String) = "photo/$photoId"
    fun compare(a: String, b: String) = "compare/$a/$b"
    const val ACADEMY = "academy"
    fun lesson(id: String) = "lesson/$id"
    const val PROGRESS = "progress"
    const val SETTINGS = "settings"
    const val MODELS = "models"
    const val STORAGE = "storage"
    const val DIAGNOSTICS = "diagnostics"
}

@Composable
fun LumenNavHost(nav: NavHostController = rememberNavController()) {
    NavHost(navController = nav, startDestination = Routes.CAMERA) {
        composable(
            Routes.CAMERA,
            arguments = listOf(
                navArgument("lesson") { type = NavType.StringType; defaultValue = "" },
                navArgument("strategy") { type = NavType.StringType; defaultValue = "" },
            ),
        ) { entry ->
            CameraRoute(
                nav = nav,
                practiceLessonId = entry.arguments?.getString("lesson")?.takeIf { it.isNotBlank() },
                practiceStrategy = entry.arguments?.getString("strategy")?.takeIf { it.isNotBlank() },
            )
        }
        composable("review/{photoId}") { ReviewScreen(nav, it.arguments?.getString("photoId").orEmpty()) }
        composable(
            "filmlab/{photoId}?edit={edit}",
            arguments = listOf(navArgument("edit") { type = NavType.StringType; defaultValue = "" }),
        ) {
            FilmLabScreen(nav, it.arguments?.getString("photoId").orEmpty(), it.arguments?.getString("edit")?.takeIf { e -> e.isNotBlank() })
        }
        composable(Routes.LIBRARY) { LibraryScreen(nav) }
        composable("photo/{photoId}") { PhotoDetailScreen(nav, it.arguments?.getString("photoId").orEmpty()) }
        composable("compare/{a}/{b}") { CompareScreen(nav, it.arguments?.getString("a").orEmpty(), it.arguments?.getString("b").orEmpty()) }
        composable(Routes.ACADEMY) { AcademyScreen(nav) }
        composable("lesson/{id}") { LessonScreen(nav, it.arguments?.getString("id").orEmpty()) }
        composable(Routes.PROGRESS) { ProgressScreen(nav) }
        composable(Routes.SETTINGS) { SettingsScreen(nav) }
        composable(Routes.MODELS) { ModelsScreen(nav) }
        composable(Routes.STORAGE) { StorageScreen(nav) }
        composable(Routes.DIAGNOSTICS) { DiagnosticsScreen(nav) }
    }
}
