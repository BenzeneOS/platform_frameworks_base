/*
 * Copyright (C) 2024 GrapheneOS
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

package com.android.systemui.qs.tiles;

import com.android.systemui.qs.QsEventLogger;
import com.android.systemui.qs.pipeline.shared.TileSpec;
import com.android.systemui.qs.shared.model.TileCategory;
import com.android.systemui.qs.tileimpl.QSTileImpl;
import com.android.systemui.qs.tiles.base.shared.model.QSTileConfig;
import com.android.systemui.qs.tiles.base.shared.model.QSTilePolicy;
import com.android.systemui.qs.tiles.base.shared.model.QSTileUIConfig;
import com.android.systemui.res.R;

import dagger.Binds;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoMap;
import dagger.multibindings.StringKey;

/**
 * Module for injecting UsbTetherTile.
 */
@Module
public abstract class UsbTetherTileModule {

    @Binds
    @IntoMap
    @StringKey(UsbTetherTile.TILE_SPEC)
    public abstract QSTileImpl<?> bindUsbTetherTile(UsbTetherTile usbTetherTile);

    @Provides
    @IntoMap
    @StringKey(UsbTetherTile.TILE_SPEC)
    public static QSTileConfig provideUsbTetherTileConfig(QsEventLogger uiEventLogger) {
        TileSpec tileSpec = TileSpec.create(UsbTetherTile.TILE_SPEC);
        return new QSTileConfig(
                tileSpec,
                new QSTileUIConfig.Resource(
                        R.drawable.ic_qs_usb_tether,
                        R.string.quick_settings_usb_tether_label
                ),
                uiEventLogger.getNewInstanceId(),
                TileCategory.CONNECTIVITY,
                tileSpec.getSpec(),
                QSTilePolicy.NoRestrictions.INSTANCE
        );
    }
}
