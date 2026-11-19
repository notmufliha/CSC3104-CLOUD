package com.example.scamdetect

import com.example.scamdetect.data.ModelUpdater
import org.junit.Assert.assertEquals
import org.junit.Test

/** Where the model service is, from the gateway address. Same cases as iOS PrivacyLevelTests.testModelsLiveNextToApiOnTheEdge. */
class ModelUpdaterTest {
    @Test fun modelsLiveNextToApiOnTheEdge() {
        assertEquals("https://1.2.3.4:8443/models/", ModelUpdater.modelsBase("https://1.2.3.4:8443/api/"))
        assertEquals("http://localhost:8088/models/", ModelUpdater.modelsBase("http://localhost:8088/api/"))
        assertEquals("http://localhost:8088/models/", ModelUpdater.modelsBase("http://localhost:8080/"))
        assertEquals("http://192.168.1.23:8088/models/", ModelUpdater.modelsBase("192.168.1.23:8080"))
        assertEquals("http://example.org/models/", ModelUpdater.modelsBase("http://example.org/"))
        // the gateway port with a path: the edge serves /models at its root
        assertEquals("http://10.0.2.2:8088/models/", ModelUpdater.modelsBase("http://10.0.2.2:8080/gw/"))
        // the emulator's default address (docker compose)
        assertEquals("http://10.0.2.2:8088/models/", ModelUpdater.modelsBase("http://10.0.2.2:8080/"))
    }

    @Test fun theUploadInboxIsNextToModels() {
        assertEquals("http://10.0.2.2:8088/fl/updates", ModelUpdater.flUpdatesUrl("http://10.0.2.2:8080/"))
        assertEquals("https://1.2.3.4:8443/fl/updates", ModelUpdater.flUpdatesUrl("https://1.2.3.4:8443/api/"))
    }
}
