// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.push.international.compat

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import org.ramflux.push.api.BrandCompat
import org.ramflux.push.api.DeviceGuidance
import org.ramflux.push.international.compat.brand.AospCompat
import org.ramflux.push.international.compat.brand.AsusCompat
import org.ramflux.push.international.compat.brand.HonorCompat
import org.ramflux.push.international.compat.brand.HuaweiCompat
import org.ramflux.push.international.compat.brand.MotorolaCompat
import org.ramflux.push.international.compat.brand.NothingCompat
import org.ramflux.push.international.compat.brand.OplusCompat
import org.ramflux.push.international.compat.brand.SamsungCompat
import org.ramflux.push.international.compat.brand.SonyCompat
import org.ramflux.push.international.compat.brand.TranssionCompat
import org.ramflux.push.international.compat.brand.VivoCompat
import org.ramflux.push.international.compat.brand.XiaomiCompat

@Module
@InstallIn(SingletonComponent::class)
abstract class InternationalCompatModule {
    @Binds
    abstract fun deviceGuidance(registry: BrandRegistry): DeviceGuidance

    @Binds
    @IntoSet
    abstract fun aosp(compat: AospCompat): BrandCompat

    @Binds
    @IntoSet
    abstract fun samsung(compat: SamsungCompat): BrandCompat

    @Binds
    @IntoSet
    abstract fun xiaomi(compat: XiaomiCompat): BrandCompat

    @Binds
    @IntoSet
    abstract fun huawei(compat: HuaweiCompat): BrandCompat

    @Binds
    @IntoSet
    abstract fun honor(compat: HonorCompat): BrandCompat

    @Binds
    @IntoSet
    abstract fun oplus(compat: OplusCompat): BrandCompat

    @Binds
    @IntoSet
    abstract fun vivo(compat: VivoCompat): BrandCompat

    @Binds
    @IntoSet
    abstract fun motorola(compat: MotorolaCompat): BrandCompat

    @Binds
    @IntoSet
    abstract fun sony(compat: SonyCompat): BrandCompat

    @Binds
    @IntoSet
    abstract fun nothing(compat: NothingCompat): BrandCompat

    @Binds
    @IntoSet
    abstract fun transsion(compat: TranssionCompat): BrandCompat

    @Binds
    @IntoSet
    abstract fun asus(compat: AsusCompat): BrandCompat
}
