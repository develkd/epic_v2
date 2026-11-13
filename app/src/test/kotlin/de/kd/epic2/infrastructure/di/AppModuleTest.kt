package de.kd.epic2.infrastructure.di

import org.junit.Test
import org.koin.core.annotation.KoinExperimentalAPI
import org.koin.test.verify.verify

class AppModuleTest {
    @OptIn(KoinExperimentalAPI::class)
    @Test
    fun `app module can resolve all its definitions`() {
        appModule.verify()
    }
}
