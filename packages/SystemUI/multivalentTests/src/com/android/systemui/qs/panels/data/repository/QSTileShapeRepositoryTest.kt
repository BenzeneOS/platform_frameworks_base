/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.qs.panels.data.repository

import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.qs.panels.shared.model.QSTileShape
import com.android.systemui.shared.settings.data.repository.FakeSecureSettingsRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class QSTileShapeRepositoryTest : SysuiTestCase() {

    private lateinit var testScope: TestScope
    private lateinit var secureSettingsRepository: FakeSecureSettingsRepository
    private lateinit var underTest: QSTileShapeRepository

    @Before
    fun setUp() {
        testScope = TestScope(StandardTestDispatcher())
        secureSettingsRepository = FakeSecureSettingsRepository()
        underTest = QSTileShapeRepository(secureSettingsRepository)
    }

    @Test
    fun tileShape_tracksSettingAndFallsBackToBoth() =
        testScope.runTest {
            val tileShape by collectLastValue(underTest.tileShape)

            assertThat(tileShape).isEqualTo(QSTileShape.BOTH)

            secureSettingsRepository.setInt(
                Settings.Secure.QS_TILE_SHAPE,
                Settings.Secure.QS_TILE_SHAPE_ROUNDED,
            )
            assertThat(tileShape).isEqualTo(QSTileShape.ROUNDED)

            secureSettingsRepository.setInt(
                Settings.Secure.QS_TILE_SHAPE,
                Settings.Secure.QS_TILE_SHAPE_SQUARE,
            )
            assertThat(tileShape).isEqualTo(QSTileShape.SQUARE)

            secureSettingsRepository.setInt(Settings.Secure.QS_TILE_SHAPE, Int.MAX_VALUE)
            assertThat(tileShape).isEqualTo(QSTileShape.BOTH)
        }
}
