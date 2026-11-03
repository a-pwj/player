package com.vivi.player.di

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vivi.player.ViViApplication
import kotlin.reflect.KClass

/**
 * 界面级 ViewModel 的统一入口：依赖图从 AppContainer 拿。
 * 不引 Hilt/Koin——这个规模的注入图用显式工厂更直观，也少一套 KSP 注解处理器。
 */
@Composable
fun <VM : ViewModel> appViewModel(clazz: KClass<VM>, create: (AppContainer) -> VM): VM {
    val container = ViViApplication.from(LocalContext.current).container
    val factory = rememberFactory(container, create)
    return viewModel(modelClass = clazz.java, factory = factory)
}

@Composable
private fun <VM : ViewModel> rememberFactory(
    container: AppContainer,
    create: (AppContainer) -> VM,
): ViewModelProvider.Factory = androidx.compose.runtime.remember(container) {
    object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = create(container) as T
    }
}

@Composable
fun appContainer(): AppContainer = ViViApplication.from(LocalContext.current).container
