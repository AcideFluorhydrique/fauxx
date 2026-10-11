package com.fauxx.di

import javax.inject.Qualifier

/** Qualifier for the ad pollution module binding. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class AdModuleImpl

/** Qualifier for the location spoof module binding. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class LocationModuleImpl
