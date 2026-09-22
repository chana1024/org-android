package com.orgutil.di

import javax.inject.Qualifier

/** Marks the [kotlinx.coroutines.CoroutineDispatcher] used for blocking I/O work. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IoDispatcher
