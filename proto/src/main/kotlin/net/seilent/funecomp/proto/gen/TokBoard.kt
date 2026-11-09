package net.seilent.funecomp.proto.gen

object TokBoard {
    abstract class Board {
        abstract val u: Float
        abstract val canvasH: Float
        abstract val minTextPx: Float
        abstract val type_homeItem_size: Float
        abstract val type_homeSubtitle_size: Float
        abstract val type_quickplayHeader_size: Float
        abstract val type_quickplayHeader_alpha: Float
        abstract val type_qpHint_size: Float
        abstract val type_npCount_size: Float
        abstract val type_npViewArtist_size: Float
        abstract val type_npViewAlbum_size: Float
        abstract val type_npViewAlbum_style: String
        abstract val type_npViewTitle_size: Float
        abstract val type_npViewTitle_style: String
        abstract val type_npViewNext_size: Float
        abstract val type_npViewTime_size: Float
        abstract val type_npViewTime_style: String
        abstract val type_saverFourth_size: Float
        abstract val homeTextX: Float
        abstract val homeFirstBaseline: Float
        abstract val homeSubtitleX: Float
        abstract val homeSubtitleDrop: Float
        abstract val homeWatermarkBaseline: Float
        abstract val qpPeekX: Float
        abstract val qpPeekY: Float
        abstract val pivotTop: Float
        abstract val pivotBottom: Float
        abstract val page_pivotBaseline: Float
        abstract val page_contentTop: Float
        abstract val page_countBaseline: Float
        abstract val detailNameBaseline: Float
        abstract val detailRingTop: Float
        abstract val detailLabelBaseline: Float
        abstract val detailRowsTop: Float
        abstract val queueRowsTop: Float
        abstract val npBackRing: Float
        abstract val npBackRingStroke: Float
        abstract val npBackRingX: Float
        abstract val npBackRingY: Float
        abstract val npBackGlyph: Float
        abstract val npArtistY: Float
        abstract val npAlbumY: Float
        abstract val npFromBaseline: Float
        abstract val npArtTop: Float
        abstract val npArt: Float
        abstract val npProgressY: Float
        abstract val npBarHeight: Float
        abstract val npTimeBaseline: Float
        abstract val npTitleY: Float
        abstract val npTextX: Float
        abstract val npArtX: Float
        abstract val npTextRight: Float
        abstract val npNextIndent: Float
        abstract val npNextDrop: Float
        abstract val npNextLinePitch: Float
        abstract val npNextCount: Float
        abstract val npNextNavCount: Float
        abstract val npRing: Float
        abstract val npRingStroke: Float
        abstract val npRepeatOne: Float
        abstract val npRepeatOneR: Float
        abstract val npRingY: Float
        abstract val npRingNavY: Float
        abstract val npRingCx0: Float
        abstract val npRingCx1: Float
        abstract val npRingCx2: Float
        abstract val npStatusHeight: Float
        abstract val npStatusTop: Float
        abstract val npStatusRight: Float
        abstract val npStatusGap: Float
        abstract val npStatusText: Float
        abstract val npStatusBatteryW: Float
        abstract val npStatusBatteryH: Float
        abstract val saverFourthBaseline: Float
        abstract val lockBtGlyph: Float
        abstract val lockArtY: Float
        abstract val kb_keyH: Float
        abstract val overlayAnchorScale: Float
        abstract val overlayRingY: Float
        abstract val overlayMinusY: Float
        abstract val overlayTitleBottom: Float
        abstract val vol_plusY: Float
        abstract val vol_numberY: Float
        abstract val vol_ringY: Float
        abstract val vol_minusY: Float
        abstract val vol_slideStride: Float
        abstract val vol_exitY: Float
        abstract val vol_textY: Float
        abstract val qpEdge: Float
        abstract val qpTile: Float
        abstract val qpW: Float
        abstract val qpSectionGap: Float
        abstract val qpHintW: Float
        abstract val qpTextX: Float
        abstract val qpBaseline: Float
        abstract val qpTileTop: Float
        abstract val qpHintBase: Float
        abstract val qpHintStep: Float
        abstract val qpHintGap: Float
        abstract val qpEndPad: Float
        abstract val qpTiltAngle: Float
        abstract val homeBarX: Float
        abstract val homeBarTop: Float
        abstract val homeBarH: Float
        abstract val focusBandBottom: Float
        abstract val motion_contentHubRows: String
        abstract val motion_contentPageRows: String
        abstract val motion_slicesAlbums: Float
        abstract val motion_slicesSettings: Float
        abstract val motion_quickplay: Float
        abstract val motion_quickplayAngle: Float
        abstract val motion_quickplayScale: Float
        abstract val motion_quickplayMenuInset: Float
        abstract val motion_quickplayMenuX: Float
        abstract val motion_quickplayMenuY: Float
        abstract val motion_npPace: Float
        abstract val motion_npSlices: Float
        abstract val motion_swivelHingeY: Float
        abstract val motion_volumeTiltAngle: Float
        abstract val motion_volumeTiltDepth: Float
        abstract val motion_saverWordEntryShift: Float
        abstract val saverGiant: Float
        abstract val saverGiantDigits: Float
        abstract val saverGiantCjk: Float
        abstract val saverLarge: Float
        abstract val saverLargeBlack: Float
        abstract val saverLargeCjk: Float
        abstract val saverDigits: Float
        abstract val saverRow: Float
        abstract val saverRowE: Float
        abstract val saverSmall: Float
        abstract val saverSmallE: Float
        abstract val saverB1Size: Float
        abstract val saverF2Size: Float
        abstract val saverS1Size: Float
        abstract val saverS4Size: Float
        abstract val saverS5Size: Float
        abstract val saverTileA5: Float
        abstract val saverTileB5: Float
        abstract val saverTileB7: Float
        abstract val saverTileD5: Float
        abstract val saverTileD6: Float
        abstract val saverTileE5: Float
        abstract val saverTileE5Tall: Float
        abstract val saverTileF4: Float
        abstract val saverTileH1: Float
        abstract val saverTileH7: Float
        abstract val saverTileS3: Float
        abstract val saverTileS: Float
        abstract val saverTileM: Float
        abstract val saverTileL: Float
        abstract val saverTileK3: Float
        abstract val saverTileK6: Float
        abstract val saverTileP4: Float
        abstract val saverTileR4: Float
        abstract val saverTileS6: Float
        abstract val saverR1Size: Float
        abstract val saverR3Size: Float
        abstract val motion_saverA1Speed: Float
        abstract val motion_saverA2Speed: Float
        abstract val motion_saverA3Speed: Float
        abstract val motion_saverA4Speed: Float
        abstract val motion_saverA5Speed: Float
        abstract val motion_saverA6Speed: Float
        abstract val motion_saverB1Speed: Float
        abstract val motion_saverB2Speed: Float
        abstract val motion_saverB3Speed: Float
        abstract val motion_saverB4Speed: Float
        abstract val motion_saverB5Speed: Float
        abstract val motion_saverB6Speed: Float
        abstract val motion_saverB7Speed: Float
        abstract val motion_saverD1Speed: Float
        abstract val motion_saverD2Speed: Float
        abstract val motion_saverD3Speed: Float
        abstract val motion_saverD4Speed: Float
        abstract val motion_saverD5Speed: Float
        abstract val motion_saverD6Speed: Float
        abstract val motion_saverE1Speed: Float
        abstract val motion_saverE2Speed: Float
        abstract val motion_saverE3Speed: Float
        abstract val motion_saverE4Speed: Float
        abstract val motion_saverE5Speed: Float
        abstract val motion_saverF1Speed: Float
        abstract val motion_saverF2Speed: Float
        abstract val motion_saverF3Speed: Float
        abstract val motion_saverF4Speed: Float
        abstract val motion_saverF5Speed: Float
        abstract val motion_saverH1Speed: Float
        abstract val motion_saverH2Speed: Float
        abstract val motion_saverH3Speed: Float
        abstract val motion_saverH4Speed: Float
        abstract val motion_saverH5Speed: Float
        abstract val motion_saverH6Speed: Float
        abstract val motion_saverH7Speed: Float
        abstract val motion_saverH8Speed: Float
        abstract val motion_saverS1Speed: Float
        abstract val motion_saverS2Speed: Float
        abstract val motion_saverS3Speed: Float
        abstract val motion_saverS4Speed: Float
        abstract val motion_saverS5Speed: Float
        abstract val motion_saverS6Speed: Float
        abstract val motion_saverS7Speed: Float
        abstract val motion_saverK1Speed: Float
        abstract val motion_saverK2Speed: Float
        abstract val motion_saverK3Speed: Float
        abstract val motion_saverK4Speed: Float
        abstract val motion_saverK5Speed: Float
        abstract val motion_saverK6Speed: Float
        abstract val motion_saverP1Speed: Float
        abstract val motion_saverP2Speed: Float
        abstract val motion_saverP3Speed: Float
        abstract val motion_saverP4Speed: Float
        abstract val motion_saverP5Speed: Float
        abstract val motion_saverR1Speed: Float
        abstract val motion_saverR2Speed: Float
        abstract val motion_saverR3Speed: Float
        abstract val motion_saverR4Speed: Float
        abstract val motion_saverJ1Speed: Float
        abstract val motion_saverJ2Speed: Float
        abstract val motion_saverJ3Speed: Float
        abstract val motion_saverJ4Speed: Float
        abstract val motion_saverJ5Speed: Float
        abstract val motion_saverJ6Speed: Float
        abstract val motion_saverL1Speed: Float
        abstract val motion_saverL2Speed: Float
        abstract val motion_saverL3Speed: Float
        abstract val motion_saverL4Speed: Float
        abstract val motion_saverL5Speed: Float
        abstract val motion_saverM1Speed: Float
        abstract val motion_saverM2Speed: Float
        abstract val motion_saverM3Speed: Float
        abstract val motion_saverM4Speed: Float
        abstract val motion_saverM5Speed: Float
        abstract val motion_saverM6Speed: Float
        abstract val motion_saverN1Speed: Float
        abstract val motion_saverN2Speed: Float
        abstract val motion_saverN3Speed: Float
        abstract val motion_saverN4Speed: Float
        abstract val motion_saverN5Speed: Float
        abstract val motion_saverN6Speed: Float
        abstract val motion_saverQ1Speed: Float
        abstract val motion_saverQ2Speed: Float
        abstract val motion_saverQ3Speed: Float
        abstract val motion_saverQ4Speed: Float
        abstract val motion_saverQ5Speed: Float
        abstract val motion_saverQ6Speed: Float
        abstract val motion_saverQ7Speed: Float
        abstract val saverD1Join: Float
        abstract val saverB4Period: Float
        abstract val saverE4Period: Float
        abstract val saverS4Period: Float
        abstract val saverE2Period: Float
        abstract val saverF1Gap: Float
        abstract val saverF2Gap: Float
        abstract val saverF1Trail: Float
        abstract val saverF2Trail: Float
        abstract val motion_saverWhooshSpeed: Float
        abstract val motion_saverPWhooshSpeed: Float
        abstract val saverE1Shift: Float
        abstract val saverA1Shift: Float
        abstract val saverF1Shift: Float
        abstract val saverF2Shift: Float
        abstract val saverK1Shift: Float
        abstract val saverP1Shift: Float
        abstract val saverR1Shift: Float
        abstract val motion_saverLyrSpeedMin: Float
        abstract val motion_saverLyrSpeedMax: Float
        abstract val motion_saverLyrSlide: Float
        abstract val saverLyrGap: Float
        abstract val saverLyrSizeSum: Float
        abstract val saverLyrSizeSumUp: Float
        abstract val motion_saverLyrPoolGiantDiagLo: Float
        abstract val motion_saverLyrPoolGiantDiagHi: Float
        abstract val motion_saverLyrPoolGiantUpLo: Float
        abstract val motion_saverLyrPoolGiantUpHi: Float
        abstract val motion_saverLyrPoolRowDiagLo: Float
        abstract val motion_saverLyrPoolRowDiagHi: Float
        abstract val motion_saverLyrPoolRowUpLo: Float
        abstract val motion_saverLyrPoolRowUpHi: Float
        abstract val motion_saverLyrPoolLargeLo: Float
        abstract val motion_saverLyrPoolLargeHi: Float
        abstract val motion_saverLyrPoolSmallLo: Float
        abstract val motion_saverLyrPoolSmallHi: Float
        abstract val saver_aheadMs: Float
        abstract val saverA1Across: Float
        abstract val saverA2Across: Float
        abstract val saverA3Across: Float
        abstract val saverA4Across: Float
        abstract val saverA5Across: Float
        abstract val saverA6Across: Float
        abstract val saverB1Across: Float
        abstract val saverB2Across: Float
        abstract val saverB3Across: Float
        abstract val saverB4Across: Float
        abstract val saverB5Across: Float
        abstract val saverB6Across: Float
        abstract val saverB7Across: Float
        abstract val saverD1Across: Float
        abstract val saverD2Across: Float
        abstract val saverD3Across: Float
        abstract val saverD4Across: Float
        abstract val saverD5Across: Float
        abstract val saverD6Across: Float
        abstract val saverE1Across: Float
        abstract val saverE2Across: Float
        abstract val saverE3Across: Float
        abstract val saverE5Across: Float
        abstract val saverF1Across: Float
        abstract val saverF2Across: Float
        abstract val saverF3Across: Float
        abstract val saverF4Across: Float
        abstract val saverF5Across: Float
        abstract val saverH1Across: Float
        abstract val saverH2Across: Float
        abstract val saverH3Across: Float
        abstract val saverH4Across: Float
        abstract val saverH5Across: Float
        abstract val saverH6Across: Float
        abstract val saverH7Across: Float
        abstract val saverH8Across: Float
        abstract val saverS1Across: Float
        abstract val saverS2Across: Float
        abstract val saverS3Across: Float
        abstract val saverS4Across: Float
        abstract val saverS5Across: Float
        abstract val saverS6Across: Float
        abstract val saverS7Across: Float
        abstract val saverK1Across: Float
        abstract val saverK2Across: Float
        abstract val saverK3Across: Float
        abstract val saverK4Across: Float
        abstract val saverK5Across: Float
        abstract val saverK6Across: Float
        abstract val saverP1Across: Float
        abstract val saverP2Across: Float
        abstract val saverP3Across: Float
        abstract val saverP4Across: Float
        abstract val saverP5Across: Float
        abstract val saverR1Across: Float
        abstract val saverR2Across: Float
        abstract val saverR3Across: Float
        abstract val saverR4Across: Float
        abstract val saverJ1Across: Float
        abstract val saverJ2Across: Float
        abstract val saverJ3Across: Float
        abstract val saverJ4Across: Float
        abstract val saverJ5Across: Float
        abstract val saverJ6Across: Float
        abstract val saverL1Across: Float
        abstract val saverL2Across: Float
        abstract val saverL3Across: Float
        abstract val saverL4Across: Float
        abstract val saverL5Across: Float
        abstract val saverM1Across: Float
        abstract val saverM2Across: Float
        abstract val saverM3Across: Float
        abstract val saverM4Across: Float
        abstract val saverM5Across: Float
        abstract val saverM6Across: Float
        abstract val saverN1Across: Float
        abstract val saverN2Across: Float
        abstract val saverN3Across: Float
        abstract val saverN4Across: Float
        abstract val saverN5Across: Float
        abstract val saverN6Across: Float
        abstract val saverQ1Across: Float
        abstract val saverQ2Across: Float
        abstract val saverQ3Across: Float
        abstract val saverQ4Across: Float
        abstract val saverQ5Across: Float
        abstract val saverQ6Across: Float
        abstract val saverQ7Across: Float
        abstract val settings_filterCount: Float
        abstract val settings_maxVolumeSteps: String
        abstract val settings_powerOnSteps: String
        abstract val settings_brightnessDefault: Float
        abstract val homeRingX: Float
        abstract val homeRingY: Float
        abstract val homeRingSize: Float
        abstract val homeRingStroke: Float
        abstract val homeRingHitBottom: Float
        abstract val npSeekBandX0: Float
        abstract val npSeekBandX1: Float
        abstract val npSeekBandBottom: Float
        abstract val npLyricBottom: Float
        abstract val lockLyricBaseline: Float
        abstract val large_npLyricPitch: Float
        abstract val large_npNextPitch: Float
        abstract val large_npAlbumPitch: Float
        abstract val npNextCountLarge: Float
        abstract val npNextNavCountLarge: Float
    }

    object ap80 : Board() {
        override val u: Float = 1.5f
        override val canvasH: Float = 320f
        override val minTextPx: Float = 12f
        override val type_homeItem_size: Float = 34f
        override val type_homeSubtitle_size: Float = 8f
        override val type_quickplayHeader_size: Float = 34f
        override val type_quickplayHeader_alpha: Float = 0.6f
        override val type_qpHint_size: Float = 11f
        override val type_npCount_size: Float = 13f
        override val type_npViewArtist_size: Float = 20f
        override val type_npViewAlbum_size: Float = 20f
        override val type_npViewAlbum_style: String = "Light"
        override val type_npViewTitle_size: Float = 26f
        override val type_npViewTitle_style: String = "Light"
        override val type_npViewNext_size: Float = 13f
        override val type_npViewTime_size: Float = 13f
        override val type_npViewTime_style: String = "Regular"
        override val type_saverFourth_size: Float = 88.0f
        override val homeTextX: Float = 70f
        override val homeFirstBaseline: Float = 48f
        override val homeSubtitleX: Float = 10f
        override val homeSubtitleDrop: Float = 8f
        override val homeWatermarkBaseline: Float = 304f
        override val qpPeekX: Float = 0f
        override val qpPeekY: Float = 130f
        override val pivotTop: Float = 58f
        override val pivotBottom: Float = 86f
        override val page_pivotBaseline: Float = 79f
        override val page_contentTop: Float = 98f
        override val page_countBaseline: Float = 79f
        override val detailNameBaseline: Float = 94f
        override val detailRingTop: Float = 134f
        override val detailLabelBaseline: Float = 184f
        override val detailRowsTop: Float = 196f
        override val queueRowsTop: Float = 98f
        override val npBackRing: Float = 64f
        override val npBackRingStroke: Float = 4f
        override val npBackRingX: Float = -6f
        override val npBackRingY: Float = -15f
        override val npBackGlyph: Float = 32f
        override val npArtistY: Float = 86f
        override val npAlbumY: Float = 110f
        override val npFromBaseline: Float = 66f
        override val npArtTop: Float = 124f
        override val npArt: Float = 76f
        override val npProgressY: Float = 200f
        override val npBarHeight: Float = 2f
        override val npTimeBaseline: Float = 14f
        override val npTitleY: Float = 238f
        override val npTextX: Float = 16f
        override val npArtX: Float = 16f
        override val npTextRight: Float = 224f
        override val npNextIndent: Float = 40f
        override val npNextDrop: Float = 18f
        override val npNextLinePitch: Float = 18f
        override val npNextCount: Float = 1f
        override val npNextNavCount: Float = 1f
        override val npRing: Float = 32f
        override val npRingStroke: Float = 3f
        override val npRepeatOne: Float = 24f
        override val npRepeatOneR: Float = 16.7f
        override val npRingY: Float = 280f
        override val npRingNavY: Float = 280f
        override val npRingCx0: Float = 31.2f
        override val npRingCx1: Float = 120f
        override val npRingCx2: Float = 208.8f
        override val npStatusHeight: Float = 12f
        override val npStatusTop: Float = 10f
        override val npStatusRight: Float = 16f
        override val npStatusGap: Float = 8f
        override val npStatusText: Float = 13f
        override val npStatusBatteryW: Float = 0f
        override val npStatusBatteryH: Float = 0f
        override val saverFourthBaseline: Float = 324.0f
        override val lockBtGlyph: Float = 46.66667f
        override val lockArtY: Float = 240f
        override val kb_keyH: Float = 59.25f
        override val overlayAnchorScale: Float = 0.6667f
        override val overlayRingY: Float = 144f
        override val overlayMinusY: Float = 248f
        override val overlayTitleBottom: Float = 296f
        override val vol_plusY: Float = 72f
        override val vol_numberY: Float = 69.33f
        override val vol_ringY: Float = 154.67f
        override val vol_minusY: Float = 232f
        override val vol_slideStride: Float = 40f
        override val vol_exitY: Float = 30f
        override val vol_textY: Float = 253.33f
        override val qpEdge: Float = 60f
        override val qpTile: Float = 77f
        override val qpW: Float = 216f
        override val qpSectionGap: Float = 70f
        override val qpHintW: Float = 140f
        override val qpTextX: Float = 60f
        override val qpBaseline: Float = 35f
        override val qpTileTop: Float = 5f
        override val qpHintBase: Float = 16f
        override val qpHintStep: Float = 11f
        override val qpHintGap: Float = 70f
        override val qpEndPad: Float = 20f
        override val qpTiltAngle: Float = 0f
        override val homeBarX: Float = 62f
        override val homeBarTop: Float = 23.8f
        override val homeBarH: Float = 37.4f
        override val focusBandBottom: Float = 280f
        override val motion_contentHubRows: String = "zoom"
        override val motion_contentPageRows: String = "zoom"
        override val motion_slicesAlbums: Float = 2f
        override val motion_slicesSettings: Float = 2f
        override val motion_quickplay: Float = 420f
        override val motion_quickplayAngle: Float = -12f
        override val motion_quickplayScale: Float = 0.44f
        override val motion_quickplayMenuInset: Float = 84f
        override val motion_quickplayMenuX: Float = 156f
        override val motion_quickplayMenuY: Float = 94f
        override val motion_npPace: Float = 1f
        override val motion_npSlices: Float = 2f
        override val motion_swivelHingeY: Float = 160f
        override val motion_volumeTiltAngle: Float = 17.19f
        override val motion_volumeTiltDepth: Float = 12.5f
        override val motion_saverWordEntryShift: Float = 66.7f
        override val saverGiant: Float = 214f
        override val saverGiantDigits: Float = 138f
        override val saverGiantCjk: Float = 136f
        override val saverLarge: Float = 90.7f
        override val saverLargeBlack: Float = 92.7f
        override val saverLargeCjk: Float = 52f
        override val saverDigits: Float = 56f
        override val saverRow: Float = 37.7f
        override val saverRowE: Float = 38f
        override val saverSmall: Float = 19f
        override val saverSmallE: Float = 19.7f
        override val saverB1Size: Float = 170.7f
        override val saverF2Size: Float = 88.5f
        override val saverS1Size: Float = 33.3f
        override val saverS4Size: Float = 79.1f
        override val saverS5Size: Float = 42.9f
        override val saverTileA5: Float = 95.3f
        override val saverTileB5: Float = 94f
        override val saverTileB7: Float = 94.7f
        override val saverTileD5: Float = 100f
        override val saverTileD6: Float = 94.7f
        override val saverTileE5: Float = 41f
        override val saverTileE5Tall: Float = 41.4f
        override val saverTileF4: Float = 68.7f
        override val saverTileH1: Float = 163.7f
        override val saverTileH7: Float = 101.7f
        override val saverTileS3: Float = 43.3f
        override val saverTileS: Float = 41f
        override val saverTileM: Float = 68.7f
        override val saverTileL: Float = 95f
        override val saverTileK3: Float = 164.7f
        override val saverTileK6: Float = 62.7f
        override val saverTileP4: Float = 75.3f
        override val saverTileR4: Float = 131.3f
        override val saverTileS6: Float = 40.0f
        override val saverR1Size: Float = 58.1f
        override val saverR3Size: Float = 75.3f
        override val motion_saverA1Speed: Float = -18.7f
        override val motion_saverA2Speed: Float = 14.3f
        override val motion_saverA3Speed: Float = -22.4f
        override val motion_saverA4Speed: Float = -8.3f
        override val motion_saverA5Speed: Float = -14.5f
        override val motion_saverA6Speed: Float = 6.5f
        override val motion_saverB1Speed: Float = 16.1f
        override val motion_saverB2Speed: Float = -15.7f
        override val motion_saverB3Speed: Float = 21.4f
        override val motion_saverB4Speed: Float = -14.2f
        override val motion_saverB5Speed: Float = -17.1f
        override val motion_saverB6Speed: Float = 10.6f
        override val motion_saverB7Speed: Float = 34.3f
        override val motion_saverD1Speed: Float = -23.1f
        override val motion_saverD2Speed: Float = -29.7f
        override val motion_saverD3Speed: Float = 26.5f
        override val motion_saverD4Speed: Float = 20.1f
        override val motion_saverD5Speed: Float = 19.8f
        override val motion_saverD6Speed: Float = 21.1f
        override val motion_saverE1Speed: Float = -28.4f
        override val motion_saverE2Speed: Float = 27.8f
        override val motion_saverE3Speed: Float = 11.7f
        override val motion_saverE4Speed: Float = -19.1f
        override val motion_saverE5Speed: Float = -15f
        override val motion_saverF1Speed: Float = 25.1f
        override val motion_saverF2Speed: Float = -19.1f
        override val motion_saverF3Speed: Float = -14.1f
        override val motion_saverF4Speed: Float = 16.9f
        override val motion_saverF5Speed: Float = 23.2f
        override val motion_saverH1Speed: Float = -22.1f
        override val motion_saverH2Speed: Float = -28.9f
        override val motion_saverH3Speed: Float = -23.7f
        override val motion_saverH4Speed: Float = -28.4f
        override val motion_saverH5Speed: Float = -34.2f
        override val motion_saverH6Speed: Float = 21.7f
        override val motion_saverH7Speed: Float = 10.8f
        override val motion_saverH8Speed: Float = -23.6f
        override val motion_saverS1Speed: Float = 31.5f
        override val motion_saverS2Speed: Float = -33.9f
        override val motion_saverS3Speed: Float = 29.4f
        override val motion_saverS4Speed: Float = -37.9f
        override val motion_saverS5Speed: Float = -33.1f
        override val motion_saverS6Speed: Float = -10.7f
        override val motion_saverS7Speed: Float = 17.9f
        override val motion_saverK1Speed: Float = -26.9f
        override val motion_saverK2Speed: Float = 19.3f
        override val motion_saverK3Speed: Float = -18.4f
        override val motion_saverK4Speed: Float = -16.2f
        override val motion_saverK5Speed: Float = -37.7f
        override val motion_saverK6Speed: Float = 13.8f
        override val motion_saverP1Speed: Float = -33.8f
        override val motion_saverP2Speed: Float = 36.6f
        override val motion_saverP3Speed: Float = -19.5f
        override val motion_saverP4Speed: Float = -17.2f
        override val motion_saverP5Speed: Float = 29.9f
        override val motion_saverR1Speed: Float = -24.9f
        override val motion_saverR2Speed: Float = -36.7f
        override val motion_saverR3Speed: Float = -20.6f
        override val motion_saverR4Speed: Float = -12.0f
        override val motion_saverJ1Speed: Float = 18.6f
        override val motion_saverJ2Speed: Float = -22.0f
        override val motion_saverJ3Speed: Float = -26.0f
        override val motion_saverJ4Speed: Float = 20.0f
        override val motion_saverJ5Speed: Float = 22.4f
        override val motion_saverJ6Speed: Float = 12.9f
        override val motion_saverL1Speed: Float = 15.1f
        override val motion_saverL2Speed: Float = -16.1f
        override val motion_saverL3Speed: Float = -11.6f
        override val motion_saverL4Speed: Float = 17.0f
        override val motion_saverL5Speed: Float = 24.5f
        override val motion_saverM1Speed: Float = -30.3f
        override val motion_saverM2Speed: Float = 26.2f
        override val motion_saverM3Speed: Float = -31.6f
        override val motion_saverM4Speed: Float = 29.1f
        override val motion_saverM5Speed: Float = -17.3f
        override val motion_saverM6Speed: Float = -28.9f
        override val motion_saverN1Speed: Float = -27.7f
        override val motion_saverN2Speed: Float = 20.9f
        override val motion_saverN3Speed: Float = -18.3f
        override val motion_saverN4Speed: Float = 21.7f
        override val motion_saverN5Speed: Float = 29.4f
        override val motion_saverN6Speed: Float = -35.8f
        override val motion_saverQ1Speed: Float = -31.9f
        override val motion_saverQ2Speed: Float = -24.2f
        override val motion_saverQ3Speed: Float = 25.0f
        override val motion_saverQ4Speed: Float = 14.6f
        override val motion_saverQ5Speed: Float = -32.7f
        override val motion_saverQ6Speed: Float = 14.2f
        override val motion_saverQ7Speed: Float = 26.1f
        override val saverD1Join: Float = 647.7f
        override val saverB4Period: Float = 481.2f
        override val saverE4Period: Float = 810.7f
        override val saverS4Period: Float = 1085.7f
        override val saverE2Period: Float = 1181.7f
        override val saverF1Gap: Float = 280.0f
        override val saverF2Gap: Float = 236.0f
        override val saverF1Trail: Float = -886.3f
        override val saverF2Trail: Float = 690.4f
        override val motion_saverWhooshSpeed: Float = 580.0f
        override val motion_saverPWhooshSpeed: Float = 1078.7f
        override val saverE1Shift: Float = 15.1f
        override val saverA1Shift: Float = 88.5f
        override val saverF1Shift: Float = 199.7f
        override val saverF2Shift: Float = 268.3f
        override val saverK1Shift: Float = 58.4f
        override val saverP1Shift: Float = 40.1f
        override val saverR1Shift: Float = 19.1f
        override val motion_saverLyrSpeedMin: Float = 8.28f
        override val motion_saverLyrSpeedMax: Float = 36.67f
        override val motion_saverLyrSlide: Float = 1173.33f
        override val saverLyrGap: Float = 1.33f
        override val saverLyrSizeSum: Float = 734.50f
        override val saverLyrSizeSumUp: Float = 1001.50f
        override val motion_saverLyrPoolGiantDiagLo: Float = 14.06f
        override val motion_saverLyrPoolGiantDiagHi: Float = 23.13f
        override val motion_saverLyrPoolGiantUpLo: Float = 26.87f
        override val motion_saverLyrPoolGiantUpHi: Float = 33.75f
        override val motion_saverLyrPoolRowDiagLo: Float = 14.27f
        override val motion_saverLyrPoolRowDiagHi: Float = 29.69f
        override val motion_saverLyrPoolRowUpLo: Float = 19.27f
        override val motion_saverLyrPoolRowUpHi: Float = 33.06f
        override val motion_saverLyrPoolLargeLo: Float = 14.15f
        override val motion_saverLyrPoolLargeHi: Float = 36.67f
        override val motion_saverLyrPoolSmallLo: Float = 8.28f
        override val motion_saverLyrPoolSmallHi: Float = 36.61f
        override val saver_aheadMs: Float = 9.6f
        override val saverA1Across: Float = 97.5f
        override val saverA2Across: Float = 125.4f
        override val saverA3Across: Float = 140.1f
        override val saverA4Across: Float = 153.4f
        override val saverA5Across: Float = -65.9f
        override val saverA6Across: Float = -44.3f
        override val saverB1Across: Float = 159.5f
        override val saverB2Across: Float = 42.1f
        override val saverB3Across: Float = 12.8f
        override val saverB4Across: Float = -90.5f
        override val saverB5Across: Float = 0.1f
        override val saverB6Across: Float = -99.9f
        override val saverB7Across: Float = 168.1f
        override val saverD1Across: Float = 82.1f
        override val saverD2Across: Float = -24.3f
        override val saverD3Across: Float = -12.5f
        override val saverD4Across: Float = -47.7f
        override val saverD5Across: Float = 176.8f
        override val saverD6Across: Float = 156.1f
        override val saverE1Across: Float = 231.3f
        override val saverE2Across: Float = 259.3f
        override val saverE3Across: Float = 81f
        override val saverE5Across: Float = 67.9f
        override val saverF1Across: Float = -27.2f
        override val saverF2Across: Float = 41.5f
        override val saverF3Across: Float = 154.8f
        override val saverF4Across: Float = 97.4f
        override val saverF5Across: Float = 50.3f
        override val saverH1Across: Float = 243f
        override val saverH2Across: Float = 230.0f
        override val saverH3Across: Float = 36f
        override val saverH4Across: Float = 240.0f
        override val saverH5Across: Float = 210.7f
        override val saverH6Across: Float = 241.3f
        override val saverH7Across: Float = 134f
        override val saverH8Across: Float = 147.3f
        override val saverS1Across: Float = 2f
        override val saverS2Across: Float = 30f
        override val saverS3Across: Float = 176.7f
        override val saverS4Across: Float = 302f
        override val saverS5Across: Float = 34f
        override val saverS6Across: Float = 200.0f
        override val saverS7Across: Float = 226.0f
        override val saverK1Across: Float = 233.3f
        override val saverK2Across: Float = 240.7f
        override val saverK3Across: Float = 218.0f
        override val saverK4Across: Float = 220.0f
        override val saverK5Across: Float = 50.7f
        override val saverK6Across: Float = 140.0f
        override val saverP1Across: Float = 175.3f
        override val saverP2Across: Float = 159.3f
        override val saverP3Across: Float = 148.0f
        override val saverP4Across: Float = 108.0f
        override val saverP5Across: Float = 32.0f
        override val saverR1Across: Float = 161.3f
        override val saverR2Across: Float = 201.3f
        override val saverR3Across: Float = 249.3f
        override val saverR4Across: Float = 367.3f
        override val saverJ1Across: Float = 159.7f
        override val saverJ2Across: Float = 43.4f
        override val saverJ3Across: Float = 9.2f
        override val saverJ4Across: Float = -51.1f
        override val saverJ5Across: Float = 20.1f
        override val saverJ6Across: Float = 97.4f
        override val saverL1Across: Float = 71.3f
        override val saverL2Across: Float = 94.6f
        override val saverL3Across: Float = -22.9f
        override val saverL4Across: Float = -33.9f
        override val saverL5Across: Float = 97.4f
        override val saverM1Across: Float = 205.0f
        override val saverM2Across: Float = 228.6f
        override val saverM3Across: Float = 290.2f
        override val saverM4Across: Float = 110.7f
        override val saverM5Across: Float = 99.8f
        override val saverM6Across: Float = 99.8f
        override val saverN1Across: Float = 231.3f
        override val saverN2Across: Float = 254.9f
        override val saverN3Across: Float = 265.9f
        override val saverN4Across: Float = 84.4f
        override val saverN5Across: Float = 2.0f
        override val saverN6Across: Float = 30.0f
        override val saverQ1Across: Float = 303.3f
        override val saverQ2Across: Float = 117.1f
        override val saverQ3Across: Float = 93.5f
        override val saverQ4Across: Float = 209.1f
        override val saverQ5Across: Float = 2.0f
        override val saverQ6Across: Float = 30.0f
        override val saverQ7Across: Float = 209.1f
        override val settings_filterCount: Float = 8f
        override val settings_maxVolumeSteps: String = "50,55,60,65,70,75,80,85,90,95,100"
        override val settings_powerOnSteps: String = "0,10,15,20,25,30,35,40,45,50,55,60,65,70,75,80,85,90,95,100"
        override val settings_brightnessDefault: Float = 55f
        override val homeRingX: Float = 50f
        override val homeRingY: Float = 42.9f
        override val homeRingSize: Float = 28.6f
        override val homeRingStroke: Float = 3f
        override val homeRingHitBottom: Float = 64f
        override val npSeekBandX0: Float = 8f
        override val npSeekBandX1: Float = 100f
        override val npSeekBandBottom: Float = 219.8f
        override val npLyricBottom: Float = 256f
        override val lockLyricBaseline: Float = 224f
        override val large_npLyricPitch: Float = 38.2f
        override val large_npNextPitch: Float = 22f
        override val large_npAlbumPitch: Float = 26f
        override val npNextCountLarge: Float = 1f
        override val npNextNavCountLarge: Float = 0f
    }

    object v1 : Board() {
        override val u: Float = 1.0f
        override val canvasH: Float = 320f
        override val minTextPx: Float = 8f
        override val type_homeItem_size: Float = 39.7f
        override val type_homeSubtitle_size: Float = 9f
        override val type_quickplayHeader_size: Float = 36.9f
        override val type_quickplayHeader_alpha: Float = 0.27f
        override val type_qpHint_size: Float = 11.7f
        override val type_npCount_size: Float = 9.45f
        override val type_npViewArtist_size: Float = 23.8f
        override val type_npViewAlbum_size: Float = 23.8f
        override val type_npViewAlbum_style: String = "Regular"
        override val type_npViewTitle_size: Float = 20.3f
        override val type_npViewTitle_style: String = "Regular"
        override val type_npViewNext_size: Float = 9.45f
        override val type_npViewTime_size: Float = 13.5f
        override val type_npViewTime_style: String = "Bold"
        override val type_saverFourth_size: Float = 88.0f
        override val homeTextX: Float = 52f
        override val homeFirstBaseline: Float = 100.6f
        override val homeSubtitleX: Float = 11.1f
        override val homeSubtitleDrop: Float = 3.5f
        override val homeWatermarkBaseline: Float = 304f
        override val qpPeekX: Float = -3.3f
        override val qpPeekY: Float = 165.1f
        override val pivotTop: Float = 58f
        override val pivotBottom: Float = 86f
        override val page_pivotBaseline: Float = 79f
        override val page_contentTop: Float = 98f
        override val page_countBaseline: Float = 79f
        override val detailNameBaseline: Float = 94f
        override val detailRingTop: Float = 134f
        override val detailLabelBaseline: Float = 184f
        override val detailRowsTop: Float = 196f
        override val queueRowsTop: Float = 98f
        override val npBackRing: Float = 68.7f
        override val npBackRingStroke: Float = 5.2f
        override val npBackRingX: Float = -3.9f
        override val npBackRingY: Float = -14.4f
        override val npBackGlyph: Float = 42.5f
        override val npArtistY: Float = 88f
        override val npAlbumY: Float = 113.4f
        override val npFromBaseline: Float = 68f
        override val npArtTop: Float = 118.7f
        override val npArt: Float = 91.5f
        override val npProgressY: Float = 210.5f
        override val npBarHeight: Float = 2.91f
        override val npTimeBaseline: Float = 15.9f
        override val npTitleY: Float = 249.7f
        override val npTextX: Float = 14.3f
        override val npArtX: Float = 14.1f
        override val npTextRight: Float = 221.5f
        override val npNextIndent: Float = 35.7f
        override val npNextDrop: Float = 15.2f
        override val npNextLinePitch: Float = 11.8f
        override val npNextCount: Float = 1f
        override val npNextNavCount: Float = 0f
        override val npRing: Float = 29.7f
        override val npRingStroke: Float = 2.5f
        override val npRepeatOne: Float = 20f
        override val npRepeatOneR: Float = 15.5f
        override val npRingY: Float = 291.8f
        override val npRingNavY: Float = 281.15f
        override val npRingCx0: Float = 31.9f
        override val npRingCx1: Float = 119.9f
        override val npRingCx2: Float = 207.4f
        override val npStatusHeight: Float = 11.5f
        override val npStatusTop: Float = 5.9f
        override val npStatusRight: Float = 13.7f
        override val npStatusGap: Float = 6.4f
        override val npStatusText: Float = 11.3f
        override val npStatusBatteryW: Float = 18.1f
        override val npStatusBatteryH: Float = 7.7f
        override val saverFourthBaseline: Float = 324.0f
        override val lockBtGlyph: Float = 47f
        override val lockArtY: Float = 240f
        override val kb_keyH: Float = 59.25f
        override val overlayAnchorScale: Float = 0.6667f
        override val overlayRingY: Float = 144f
        override val overlayMinusY: Float = 248f
        override val overlayTitleBottom: Float = 296f
        override val vol_plusY: Float = 72f
        override val vol_numberY: Float = 69.33f
        override val vol_ringY: Float = 154.67f
        override val vol_minusY: Float = 232f
        override val vol_slideStride: Float = 40f
        override val vol_exitY: Float = 30f
        override val vol_textY: Float = 253.33f
        override val qpEdge: Float = 33.7f
        override val qpTile: Float = 74.1f
        override val qpW: Float = 183.9f
        override val qpSectionGap: Float = 67.5f
        override val qpHintW: Float = 138f
        override val qpTextX: Float = 30.0f
        override val qpBaseline: Float = 98.9f
        override val qpTileTop: Float = 4.9f
        override val qpHintBase: Float = 11.8f
        override val qpHintStep: Float = 10.0f
        override val qpHintGap: Float = 71.4f
        override val qpEndPad: Float = 68.8f
        override val qpTiltAngle: Float = 17.19f
        override val homeBarX: Float = 44f
        override val homeBarTop: Float = 27.79f
        override val homeBarH: Float = 37.59f
        override val focusBandBottom: Float = 280f
        override val motion_contentHubRows: String = "zoom"
        override val motion_contentPageRows: String = "zoom"
        override val motion_slicesAlbums: Float = 2f
        override val motion_slicesSettings: Float = 2f
        override val motion_quickplay: Float = 445f
        override val motion_quickplayAngle: Float = -6f
        override val motion_quickplayScale: Float = 0.445f
        override val motion_quickplayMenuInset: Float = 75.7f
        override val motion_quickplayMenuX: Float = 164.3f
        override val motion_quickplayMenuY: Float = 133.3f
        override val motion_npPace: Float = 1f
        override val motion_npSlices: Float = 2f
        override val motion_swivelHingeY: Float = 160f
        override val motion_volumeTiltAngle: Float = 17.19f
        override val motion_volumeTiltDepth: Float = 12.5f
        override val motion_saverWordEntryShift: Float = 66.7f
        override val saverGiant: Float = 214f
        override val saverGiantDigits: Float = 138f
        override val saverGiantCjk: Float = 136f
        override val saverLarge: Float = 90.7f
        override val saverLargeBlack: Float = 92.7f
        override val saverLargeCjk: Float = 52f
        override val saverDigits: Float = 56f
        override val saverRow: Float = 37.7f
        override val saverRowE: Float = 38f
        override val saverSmall: Float = 19f
        override val saverSmallE: Float = 19.7f
        override val saverB1Size: Float = 170.7f
        override val saverF2Size: Float = 88.5f
        override val saverS1Size: Float = 33.3f
        override val saverS4Size: Float = 79.1f
        override val saverS5Size: Float = 42.9f
        override val saverTileA5: Float = 95.3f
        override val saverTileB5: Float = 94f
        override val saverTileB7: Float = 94.7f
        override val saverTileD5: Float = 100f
        override val saverTileD6: Float = 94.7f
        override val saverTileE5: Float = 41f
        override val saverTileE5Tall: Float = 41.4f
        override val saverTileF4: Float = 68.7f
        override val saverTileH1: Float = 163.7f
        override val saverTileH7: Float = 101.7f
        override val saverTileS3: Float = 43.3f
        override val saverTileS: Float = 41f
        override val saverTileM: Float = 68.7f
        override val saverTileL: Float = 95f
        override val saverTileK3: Float = 164.7f
        override val saverTileK6: Float = 62.7f
        override val saverTileP4: Float = 75.3f
        override val saverTileR4: Float = 131.3f
        override val saverTileS6: Float = 40.0f
        override val saverR1Size: Float = 58.1f
        override val saverR3Size: Float = 75.3f
        override val motion_saverA1Speed: Float = -18.7f
        override val motion_saverA2Speed: Float = 14.3f
        override val motion_saverA3Speed: Float = -22.4f
        override val motion_saverA4Speed: Float = -8.3f
        override val motion_saverA5Speed: Float = -14.5f
        override val motion_saverA6Speed: Float = 6.5f
        override val motion_saverB1Speed: Float = 16.1f
        override val motion_saverB2Speed: Float = -15.7f
        override val motion_saverB3Speed: Float = 21.4f
        override val motion_saverB4Speed: Float = -14.2f
        override val motion_saverB5Speed: Float = -17.1f
        override val motion_saverB6Speed: Float = 10.6f
        override val motion_saverB7Speed: Float = 34.3f
        override val motion_saverD1Speed: Float = -23.1f
        override val motion_saverD2Speed: Float = -29.7f
        override val motion_saverD3Speed: Float = 26.5f
        override val motion_saverD4Speed: Float = 20.1f
        override val motion_saverD5Speed: Float = 19.8f
        override val motion_saverD6Speed: Float = 21.1f
        override val motion_saverE1Speed: Float = -28.4f
        override val motion_saverE2Speed: Float = 27.8f
        override val motion_saverE3Speed: Float = 11.7f
        override val motion_saverE4Speed: Float = -19.1f
        override val motion_saverE5Speed: Float = -15f
        override val motion_saverF1Speed: Float = 25.1f
        override val motion_saverF2Speed: Float = -19.1f
        override val motion_saverF3Speed: Float = -14.1f
        override val motion_saverF4Speed: Float = 16.9f
        override val motion_saverF5Speed: Float = 23.2f
        override val motion_saverH1Speed: Float = -22.1f
        override val motion_saverH2Speed: Float = -28.9f
        override val motion_saverH3Speed: Float = -23.7f
        override val motion_saverH4Speed: Float = -28.4f
        override val motion_saverH5Speed: Float = -34.2f
        override val motion_saverH6Speed: Float = 21.7f
        override val motion_saverH7Speed: Float = 10.8f
        override val motion_saverH8Speed: Float = -23.6f
        override val motion_saverS1Speed: Float = 31.5f
        override val motion_saverS2Speed: Float = -33.9f
        override val motion_saverS3Speed: Float = 29.4f
        override val motion_saverS4Speed: Float = -37.9f
        override val motion_saverS5Speed: Float = -33.1f
        override val motion_saverS6Speed: Float = -10.7f
        override val motion_saverS7Speed: Float = 17.9f
        override val motion_saverK1Speed: Float = -26.9f
        override val motion_saverK2Speed: Float = 19.3f
        override val motion_saverK3Speed: Float = -18.4f
        override val motion_saverK4Speed: Float = -16.2f
        override val motion_saverK5Speed: Float = -37.7f
        override val motion_saverK6Speed: Float = 13.8f
        override val motion_saverP1Speed: Float = -33.8f
        override val motion_saverP2Speed: Float = 36.6f
        override val motion_saverP3Speed: Float = -19.5f
        override val motion_saverP4Speed: Float = -17.2f
        override val motion_saverP5Speed: Float = 29.9f
        override val motion_saverR1Speed: Float = -24.9f
        override val motion_saverR2Speed: Float = -36.7f
        override val motion_saverR3Speed: Float = -20.6f
        override val motion_saverR4Speed: Float = -12.0f
        override val motion_saverJ1Speed: Float = 18.6f
        override val motion_saverJ2Speed: Float = -22.0f
        override val motion_saverJ3Speed: Float = -26.0f
        override val motion_saverJ4Speed: Float = 20.0f
        override val motion_saverJ5Speed: Float = 22.4f
        override val motion_saverJ6Speed: Float = 12.9f
        override val motion_saverL1Speed: Float = 15.1f
        override val motion_saverL2Speed: Float = -16.1f
        override val motion_saverL3Speed: Float = -11.6f
        override val motion_saverL4Speed: Float = 17.0f
        override val motion_saverL5Speed: Float = 24.5f
        override val motion_saverM1Speed: Float = -30.3f
        override val motion_saverM2Speed: Float = 26.2f
        override val motion_saverM3Speed: Float = -31.6f
        override val motion_saverM4Speed: Float = 29.1f
        override val motion_saverM5Speed: Float = -17.3f
        override val motion_saverM6Speed: Float = -28.9f
        override val motion_saverN1Speed: Float = -27.7f
        override val motion_saverN2Speed: Float = 20.9f
        override val motion_saverN3Speed: Float = -18.3f
        override val motion_saverN4Speed: Float = 21.7f
        override val motion_saverN5Speed: Float = 29.4f
        override val motion_saverN6Speed: Float = -35.8f
        override val motion_saverQ1Speed: Float = -31.9f
        override val motion_saverQ2Speed: Float = -24.2f
        override val motion_saverQ3Speed: Float = 25.0f
        override val motion_saverQ4Speed: Float = 14.6f
        override val motion_saverQ5Speed: Float = -32.7f
        override val motion_saverQ6Speed: Float = 14.2f
        override val motion_saverQ7Speed: Float = 26.1f
        override val saverD1Join: Float = 647.7f
        override val saverB4Period: Float = 481.2f
        override val saverE4Period: Float = 810.7f
        override val saverS4Period: Float = 1085.7f
        override val saverE2Period: Float = 1181.7f
        override val saverF1Gap: Float = 280.0f
        override val saverF2Gap: Float = 236.0f
        override val saverF1Trail: Float = -886.3f
        override val saverF2Trail: Float = 690.4f
        override val motion_saverWhooshSpeed: Float = 580.0f
        override val motion_saverPWhooshSpeed: Float = 1078.7f
        override val saverE1Shift: Float = 15.1f
        override val saverA1Shift: Float = 88.5f
        override val saverF1Shift: Float = 199.7f
        override val saverF2Shift: Float = 268.3f
        override val saverK1Shift: Float = 58.4f
        override val saverP1Shift: Float = 40.1f
        override val saverR1Shift: Float = 19.1f
        override val motion_saverLyrSpeedMin: Float = 8.28f
        override val motion_saverLyrSpeedMax: Float = 36.67f
        override val motion_saverLyrSlide: Float = 1173.33f
        override val saverLyrGap: Float = 1.33f
        override val saverLyrSizeSum: Float = 734.50f
        override val saverLyrSizeSumUp: Float = 1001.50f
        override val motion_saverLyrPoolGiantDiagLo: Float = 14.06f
        override val motion_saverLyrPoolGiantDiagHi: Float = 23.13f
        override val motion_saverLyrPoolGiantUpLo: Float = 26.87f
        override val motion_saverLyrPoolGiantUpHi: Float = 33.75f
        override val motion_saverLyrPoolRowDiagLo: Float = 14.27f
        override val motion_saverLyrPoolRowDiagHi: Float = 29.69f
        override val motion_saverLyrPoolRowUpLo: Float = 19.27f
        override val motion_saverLyrPoolRowUpHi: Float = 33.06f
        override val motion_saverLyrPoolLargeLo: Float = 14.15f
        override val motion_saverLyrPoolLargeHi: Float = 36.67f
        override val motion_saverLyrPoolSmallLo: Float = 8.28f
        override val motion_saverLyrPoolSmallHi: Float = 36.61f
        override val saver_aheadMs: Float = 9.6f
        override val saverA1Across: Float = 97.5f
        override val saverA2Across: Float = 125.4f
        override val saverA3Across: Float = 140.1f
        override val saverA4Across: Float = 153.4f
        override val saverA5Across: Float = -65.9f
        override val saverA6Across: Float = -44.3f
        override val saverB1Across: Float = 159.5f
        override val saverB2Across: Float = 42.1f
        override val saverB3Across: Float = 12.8f
        override val saverB4Across: Float = -90.5f
        override val saverB5Across: Float = 0.1f
        override val saverB6Across: Float = -99.9f
        override val saverB7Across: Float = 168.1f
        override val saverD1Across: Float = 82.1f
        override val saverD2Across: Float = -24.3f
        override val saverD3Across: Float = -12.5f
        override val saverD4Across: Float = -47.7f
        override val saverD5Across: Float = 176.8f
        override val saverD6Across: Float = 156.1f
        override val saverE1Across: Float = 231.3f
        override val saverE2Across: Float = 259.3f
        override val saverE3Across: Float = 81f
        override val saverE5Across: Float = 67.9f
        override val saverF1Across: Float = -27.2f
        override val saverF2Across: Float = 41.5f
        override val saverF3Across: Float = 154.8f
        override val saverF4Across: Float = 97.4f
        override val saverF5Across: Float = 50.3f
        override val saverH1Across: Float = 243f
        override val saverH2Across: Float = 230.0f
        override val saverH3Across: Float = 36f
        override val saverH4Across: Float = 240.0f
        override val saverH5Across: Float = 210.7f
        override val saverH6Across: Float = 241.3f
        override val saverH7Across: Float = 134f
        override val saverH8Across: Float = 147.3f
        override val saverS1Across: Float = 2f
        override val saverS2Across: Float = 30f
        override val saverS3Across: Float = 176.7f
        override val saverS4Across: Float = 302f
        override val saverS5Across: Float = 34f
        override val saverS6Across: Float = 200.0f
        override val saverS7Across: Float = 226.0f
        override val saverK1Across: Float = 233.3f
        override val saverK2Across: Float = 240.7f
        override val saverK3Across: Float = 218.0f
        override val saverK4Across: Float = 220.0f
        override val saverK5Across: Float = 50.7f
        override val saverK6Across: Float = 140.0f
        override val saverP1Across: Float = 175.3f
        override val saverP2Across: Float = 159.3f
        override val saverP3Across: Float = 148.0f
        override val saverP4Across: Float = 108.0f
        override val saverP5Across: Float = 32.0f
        override val saverR1Across: Float = 161.3f
        override val saverR2Across: Float = 201.3f
        override val saverR3Across: Float = 249.3f
        override val saverR4Across: Float = 367.3f
        override val saverJ1Across: Float = 159.7f
        override val saverJ2Across: Float = 43.4f
        override val saverJ3Across: Float = 9.2f
        override val saverJ4Across: Float = -51.1f
        override val saverJ5Across: Float = 20.1f
        override val saverJ6Across: Float = 97.4f
        override val saverL1Across: Float = 71.3f
        override val saverL2Across: Float = 94.6f
        override val saverL3Across: Float = -22.9f
        override val saverL4Across: Float = -33.9f
        override val saverL5Across: Float = 97.4f
        override val saverM1Across: Float = 205.0f
        override val saverM2Across: Float = 228.6f
        override val saverM3Across: Float = 290.2f
        override val saverM4Across: Float = 110.7f
        override val saverM5Across: Float = 99.8f
        override val saverM6Across: Float = 99.8f
        override val saverN1Across: Float = 231.3f
        override val saverN2Across: Float = 254.9f
        override val saverN3Across: Float = 265.9f
        override val saverN4Across: Float = 84.4f
        override val saverN5Across: Float = 2.0f
        override val saverN6Across: Float = 30.0f
        override val saverQ1Across: Float = 303.3f
        override val saverQ2Across: Float = 117.1f
        override val saverQ3Across: Float = 93.5f
        override val saverQ4Across: Float = 209.1f
        override val saverQ5Across: Float = 2.0f
        override val saverQ6Across: Float = 30.0f
        override val saverQ7Across: Float = 209.1f
        override val settings_filterCount: Float = 5f
        override val settings_maxVolumeSteps: String = "5,10,15,20,25,30,35,40,45,50,55,60,65,70,75,80,85,90,95,100"
        override val settings_powerOnSteps: String = "0,1,5,10,15,20,25,30,35,40,45,50,55,60,65,70,75,80,85,90,95,100"
        override val settings_brightnessDefault: Float = 35f
        override val homeRingX: Float = 25.7f
        override val homeRingY: Float = 91.6f
        override val homeRingSize: Float = 35f
        override val homeRingStroke: Float = 4f
        override val homeRingHitBottom: Float = 115.9f
        override val npSeekBandX0: Float = 6.1f
        override val npSeekBandX1: Float = 113.6f
        override val npSeekBandBottom: Float = 235.49f
        override val npLyricBottom: Float = 256f
        override val lockLyricBaseline: Float = 224f
        override val large_npLyricPitch: Float = 38.2f
        override val large_npNextPitch: Float = 22f
        override val large_npAlbumPitch: Float = 26f
        override val npNextCountLarge: Float = 1f
        override val npNextNavCountLarge: Float = 0f
    }

    object r1 : Board() {
        override val u: Float = 2.0f
        override val canvasH: Float = 400f
        override val minTextPx: Float = 16f
        override val type_homeItem_size: Float = 39.7f
        override val type_homeSubtitle_size: Float = 9f
        override val type_quickplayHeader_size: Float = 36.9f
        override val type_quickplayHeader_alpha: Float = 0.27f
        override val type_qpHint_size: Float = 11.7f
        override val type_npCount_size: Float = 9.45f
        override val type_npViewArtist_size: Float = 23.8f
        override val type_npViewAlbum_size: Float = 23.8f
        override val type_npViewAlbum_style: String = "Regular"
        override val type_npViewTitle_size: Float = 20.3f
        override val type_npViewTitle_style: String = "Regular"
        override val type_npViewNext_size: Float = 9.45f
        override val type_npViewTime_size: Float = 13.5f
        override val type_npViewTime_style: String = "Bold"
        override val type_saverFourth_size: Float = 110.0f
        override val homeTextX: Float = 52f
        override val homeFirstBaseline: Float = 100.6f
        override val homeSubtitleX: Float = 11.1f
        override val homeSubtitleDrop: Float = 3.5f
        override val homeWatermarkBaseline: Float = 384f
        override val qpPeekX: Float = -3.3f
        override val qpPeekY: Float = 165.1f
        override val pivotTop: Float = 63f
        override val pivotBottom: Float = 92.5f
        override val page_pivotBaseline: Float = 85.5f
        override val page_contentTop: Float = 120f
        override val page_countBaseline: Float = 85.5f
        override val detailNameBaseline: Float = 100.5f
        override val detailRingTop: Float = 140.5f
        override val detailLabelBaseline: Float = 190.5f
        override val detailRowsTop: Float = 202.5f
        override val queueRowsTop: Float = 120f
        override val npBackRing: Float = 68.7f
        override val npBackRingStroke: Float = 5.2f
        override val npBackRingX: Float = -3.9f
        override val npBackRingY: Float = -14.4f
        override val npBackGlyph: Float = 42.5f
        override val npArtistY: Float = 87.5f
        override val npAlbumY: Float = 111.9f
        override val npFromBaseline: Float = 67.5f
        override val npArtTop: Float = 117.2f
        override val npArt: Float = 149.4f
        override val npProgressY: Float = 266.6f
        override val npBarHeight: Float = 2.91f
        override val npTimeBaseline: Float = 15.9f
        override val npTitleY: Float = 306.1f
        override val npTextX: Float = 14.3f
        override val npArtX: Float = 14.1f
        override val npTextRight: Float = 221.5f
        override val npNextIndent: Float = 35.7f
        override val npNextDrop: Float = 15.2f
        override val npNextLinePitch: Float = 11.8f
        override val npNextCount: Float = 3f
        override val npNextNavCount: Float = 2f
        override val npRing: Float = 29.7f
        override val npRingStroke: Float = 2.5f
        override val npRepeatOne: Float = 20f
        override val npRepeatOneR: Float = 15.5f
        override val npRingY: Float = 371.8f
        override val npRingNavY: Float = 361.15f
        override val npRingCx0: Float = 31.9f
        override val npRingCx1: Float = 119.9f
        override val npRingCx2: Float = 207.4f
        override val npStatusHeight: Float = 11.5f
        override val npStatusTop: Float = 5.9f
        override val npStatusRight: Float = 13.7f
        override val npStatusGap: Float = 7.7f
        override val npStatusText: Float = 11.3f
        override val npStatusBatteryW: Float = 18.1f
        override val npStatusBatteryH: Float = 7.7f
        override val saverFourthBaseline: Float = 405.0f
        override val lockBtGlyph: Float = 22.5f
        override val lockArtY: Float = 320f
        override val kb_keyH: Float = 79.25f
        override val overlayAnchorScale: Float = 0.8333f
        override val overlayRingY: Float = 180f
        override val overlayMinusY: Float = 310f
        override val overlayTitleBottom: Float = 376f
        override val vol_plusY: Float = 90f
        override val vol_numberY: Float = 86.67f
        override val vol_ringY: Float = 193.33f
        override val vol_minusY: Float = 290f
        override val vol_slideStride: Float = 50f
        override val vol_exitY: Float = 37.5f
        override val vol_textY: Float = 316.67f
        override val qpEdge: Float = 33.7f
        override val qpTile: Float = 74.1f
        override val qpW: Float = 183.9f
        override val qpSectionGap: Float = 67.5f
        override val qpHintW: Float = 138f
        override val qpTextX: Float = 30.0f
        override val qpBaseline: Float = 98.9f
        override val qpTileTop: Float = 4.9f
        override val qpHintBase: Float = 11.8f
        override val qpHintStep: Float = 10.0f
        override val qpHintGap: Float = 71.4f
        override val qpEndPad: Float = 68.8f
        override val qpTiltAngle: Float = 0f
        override val homeBarX: Float = 44f
        override val homeBarTop: Float = 27.79f
        override val homeBarH: Float = 37.59f
        override val focusBandBottom: Float = 360f
        override val motion_contentHubRows: String = "rest"
        override val motion_contentPageRows: String = "rest"
        override val motion_slicesAlbums: Float = 4f
        override val motion_slicesSettings: Float = 4f
        override val motion_quickplay: Float = 445f
        override val motion_quickplayAngle: Float = -6f
        override val motion_quickplayScale: Float = 0.445f
        override val motion_quickplayMenuInset: Float = 75.7f
        override val motion_quickplayMenuX: Float = 164.3f
        override val motion_quickplayMenuY: Float = 133.3f
        override val motion_npPace: Float = 2f
        override val motion_npSlices: Float = 4f
        override val motion_swivelHingeY: Float = 200f
        override val motion_volumeTiltAngle: Float = 0f
        override val motion_volumeTiltDepth: Float = 0f
        override val motion_saverWordEntryShift: Float = 83.3f
        override val saverGiant: Float = 267.5f
        override val saverGiantDigits: Float = 172.5f
        override val saverGiantCjk: Float = 170f
        override val saverLarge: Float = 113.3f
        override val saverLargeBlack: Float = 115.8f
        override val saverLargeCjk: Float = 65f
        override val saverDigits: Float = 70f
        override val saverRow: Float = 47.1f
        override val saverRowE: Float = 47.5f
        override val saverSmall: Float = 23.8f
        override val saverSmallE: Float = 24.7f
        override val saverB1Size: Float = 213.3f
        override val saverF2Size: Float = 110.7f
        override val saverS1Size: Float = 41.7f
        override val saverS4Size: Float = 98.9f
        override val saverS5Size: Float = 53.6f
        override val saverTileA5: Float = 119.2f
        override val saverTileB5: Float = 117.5f
        override val saverTileB7: Float = 118.3f
        override val saverTileD5: Float = 125f
        override val saverTileD6: Float = 118.3f
        override val saverTileE5: Float = 51.2f
        override val saverTileE5Tall: Float = 51.8f
        override val saverTileF4: Float = 85.8f
        override val saverTileH1: Float = 204.6f
        override val saverTileH7: Float = 127.1f
        override val saverTileS3: Float = 54.2f
        override val saverTileS: Float = 51.2f
        override val saverTileM: Float = 85.8f
        override val saverTileL: Float = 118.8f
        override val saverTileK3: Float = 205.8f
        override val saverTileK6: Float = 78.3f
        override val saverTileP4: Float = 94.2f
        override val saverTileR4: Float = 164.2f
        override val saverTileS6: Float = 50.0f
        override val saverR1Size: Float = 72.6f
        override val saverR3Size: Float = 94.1f
        override val motion_saverA1Speed: Float = -23.4f
        override val motion_saverA2Speed: Float = 17.8f
        override val motion_saverA3Speed: Float = -28f
        override val motion_saverA4Speed: Float = -10.3f
        override val motion_saverA5Speed: Float = -18.1f
        override val motion_saverA6Speed: Float = 8.1f
        override val motion_saverB1Speed: Float = 20.1f
        override val motion_saverB2Speed: Float = -19.6f
        override val motion_saverB3Speed: Float = 26.8f
        override val motion_saverB4Speed: Float = -17.7f
        override val motion_saverB5Speed: Float = -21.4f
        override val motion_saverB6Speed: Float = 13.3f
        override val motion_saverB7Speed: Float = 42.9f
        override val motion_saverD1Speed: Float = -28.9f
        override val motion_saverD2Speed: Float = -37.1f
        override val motion_saverD3Speed: Float = 33.1f
        override val motion_saverD4Speed: Float = 25.1f
        override val motion_saverD5Speed: Float = 24.8f
        override val motion_saverD6Speed: Float = 26.3f
        override val motion_saverE1Speed: Float = -35.5f
        override val motion_saverE2Speed: Float = 34.8f
        override val motion_saverE3Speed: Float = 14.7f
        override val motion_saverE4Speed: Float = -23.9f
        override val motion_saverE5Speed: Float = -18.7f
        override val motion_saverF1Speed: Float = 31.4f
        override val motion_saverF2Speed: Float = -23.8f
        override val motion_saverF3Speed: Float = -17.6f
        override val motion_saverF4Speed: Float = 21.1f
        override val motion_saverF5Speed: Float = 29.0f
        override val motion_saverH1Speed: Float = -27.7f
        override val motion_saverH2Speed: Float = -36.1f
        override val motion_saverH3Speed: Float = -29.6f
        override val motion_saverH4Speed: Float = -35.5f
        override val motion_saverH5Speed: Float = -42.8f
        override val motion_saverH6Speed: Float = 27.1f
        override val motion_saverH7Speed: Float = 13.4f
        override val motion_saverH8Speed: Float = -29.5f
        override val motion_saverS1Speed: Float = 39.4f
        override val motion_saverS2Speed: Float = -42.3f
        override val motion_saverS3Speed: Float = 36.7f
        override val motion_saverS4Speed: Float = -47.4f
        override val motion_saverS5Speed: Float = -41.3f
        override val motion_saverS6Speed: Float = -13.4f
        override val motion_saverS7Speed: Float = 22.4f
        override val motion_saverK1Speed: Float = -33.6f
        override val motion_saverK2Speed: Float = 24.1f
        override val motion_saverK3Speed: Float = -23.0f
        override val motion_saverK4Speed: Float = -20.2f
        override val motion_saverK5Speed: Float = -47.1f
        override val motion_saverK6Speed: Float = 17.2f
        override val motion_saverP1Speed: Float = -42.2f
        override val motion_saverP2Speed: Float = 45.8f
        override val motion_saverP3Speed: Float = -24.4f
        override val motion_saverP4Speed: Float = -21.5f
        override val motion_saverP5Speed: Float = 37.4f
        override val motion_saverR1Speed: Float = -31.2f
        override val motion_saverR2Speed: Float = -45.9f
        override val motion_saverR3Speed: Float = -25.8f
        override val motion_saverR4Speed: Float = -15.0f
        override val motion_saverJ1Speed: Float = 23.3f
        override val motion_saverJ2Speed: Float = -27.5f
        override val motion_saverJ3Speed: Float = -32.5f
        override val motion_saverJ4Speed: Float = 25.1f
        override val motion_saverJ5Speed: Float = 28.1f
        override val motion_saverJ6Speed: Float = 16.1f
        override val motion_saverL1Speed: Float = 18.9f
        override val motion_saverL2Speed: Float = -20.1f
        override val motion_saverL3Speed: Float = -14.5f
        override val motion_saverL4Speed: Float = 21.2f
        override val motion_saverL5Speed: Float = 30.6f
        override val motion_saverM1Speed: Float = -37.9f
        override val motion_saverM2Speed: Float = 32.7f
        override val motion_saverM3Speed: Float = -39.5f
        override val motion_saverM4Speed: Float = 36.4f
        override val motion_saverM5Speed: Float = -21.6f
        override val motion_saverM6Speed: Float = -36.1f
        override val motion_saverN1Speed: Float = -34.6f
        override val motion_saverN2Speed: Float = 26.1f
        override val motion_saverN3Speed: Float = -22.9f
        override val motion_saverN4Speed: Float = 27.2f
        override val motion_saverN5Speed: Float = 36.8f
        override val motion_saverN6Speed: Float = -44.8f
        override val motion_saverQ1Speed: Float = -39.9f
        override val motion_saverQ2Speed: Float = -30.2f
        override val motion_saverQ3Speed: Float = 31.3f
        override val motion_saverQ4Speed: Float = 18.2f
        override val motion_saverQ5Speed: Float = -40.8f
        override val motion_saverQ6Speed: Float = 17.7f
        override val motion_saverQ7Speed: Float = 32.7f
        override val saverD1Join: Float = 809.7f
        override val saverB4Period: Float = 601.5f
        override val saverE4Period: Float = 1013.3f
        override val saverS4Period: Float = 1357.1f
        override val saverE2Period: Float = 1477.1f
        override val saverF1Gap: Float = 350.0f
        override val saverF2Gap: Float = 295.0f
        override val saverF1Trail: Float = -1107.8f
        override val saverF2Trail: Float = 863.0f
        override val motion_saverWhooshSpeed: Float = 725.0f
        override val motion_saverPWhooshSpeed: Float = 1348.3f
        override val saverE1Shift: Float = 18.8f
        override val saverA1Shift: Float = 110.7f
        override val saverF1Shift: Float = 249.6f
        override val saverF2Shift: Float = 335.4f
        override val saverK1Shift: Float = 73.0f
        override val saverP1Shift: Float = 50.1f
        override val saverR1Shift: Float = 23.8f
        override val motion_saverLyrSpeedMin: Float = 10.35f
        override val motion_saverLyrSpeedMax: Float = 45.83f
        override val motion_saverLyrSlide: Float = 1466.67f
        override val saverLyrGap: Float = 1.67f
        override val saverLyrSizeSum: Float = 918.13f
        override val saverLyrSizeSumUp: Float = 1251.88f
        override val motion_saverLyrPoolGiantDiagLo: Float = 17.57f
        override val motion_saverLyrPoolGiantDiagHi: Float = 28.92f
        override val motion_saverLyrPoolGiantUpLo: Float = 33.59f
        override val motion_saverLyrPoolGiantUpHi: Float = 42.19f
        override val motion_saverLyrPoolRowDiagLo: Float = 17.84f
        override val motion_saverLyrPoolRowDiagHi: Float = 37.11f
        override val motion_saverLyrPoolRowUpLo: Float = 24.08f
        override val motion_saverLyrPoolRowUpHi: Float = 41.33f
        override val motion_saverLyrPoolLargeLo: Float = 17.69f
        override val motion_saverLyrPoolLargeHi: Float = 45.83f
        override val motion_saverLyrPoolSmallLo: Float = 10.35f
        override val motion_saverLyrPoolSmallHi: Float = 45.77f
        override val saver_aheadMs: Float = 51f
        override val saverA1Across: Float = 121.9f
        override val saverA2Across: Float = 156.8f
        override val saverA3Across: Float = 175.1f
        override val saverA4Across: Float = 191.8f
        override val saverA5Across: Float = -82.4f
        override val saverA6Across: Float = -55.4f
        override val saverB1Across: Float = 199.3f
        override val saverB2Across: Float = 52.7f
        override val saverB3Across: Float = 16f
        override val saverB4Across: Float = -113.2f
        override val saverB5Across: Float = 0.2f
        override val saverB6Across: Float = -124.9f
        override val saverB7Across: Float = 210.1f
        override val saverD1Across: Float = 102.7f
        override val saverD2Across: Float = -30.4f
        override val saverD3Across: Float = -15.7f
        override val saverD4Across: Float = -59.6f
        override val saverD5Across: Float = 221f
        override val saverD6Across: Float = 195.1f
        override val saverE1Across: Float = 289.2f
        override val saverE2Across: Float = 324.2f
        override val saverE3Across: Float = 101.2f
        override val saverE5Across: Float = 84.9f
        override val saverF1Across: Float = -34f
        override val saverF2Across: Float = 51.8f
        override val saverF3Across: Float = 193.5f
        override val saverF4Across: Float = 121.8f
        override val saverF5Across: Float = 62.9f
        override val saverH1Across: Float = 303.8f
        override val saverH2Across: Float = 287.5f
        override val saverH3Across: Float = 45f
        override val saverH4Across: Float = 300.0f
        override val saverH5Across: Float = 263.3f
        override val saverH6Across: Float = 301.7f
        override val saverH7Across: Float = 167.5f
        override val saverH8Across: Float = 184.2f
        override val saverS1Across: Float = 2.5f
        override val saverS2Across: Float = 37.5f
        override val saverS3Across: Float = 220.8f
        override val saverS4Across: Float = 377.5f
        override val saverS5Across: Float = 42.5f
        override val saverS6Across: Float = 250.0f
        override val saverS7Across: Float = 282.5f
        override val saverK1Across: Float = 291.7f
        override val saverK2Across: Float = 300.8f
        override val saverK3Across: Float = 272.5f
        override val saverK4Across: Float = 275.0f
        override val saverK5Across: Float = 63.3f
        override val saverK6Across: Float = 175.0f
        override val saverP1Across: Float = 219.2f
        override val saverP2Across: Float = 199.2f
        override val saverP3Across: Float = 185.0f
        override val saverP4Across: Float = 135.0f
        override val saverP5Across: Float = 40.0f
        override val saverR1Across: Float = 201.7f
        override val saverR2Across: Float = 251.7f
        override val saverR3Across: Float = 311.7f
        override val saverR4Across: Float = 459.2f
        override val saverJ1Across: Float = 199.6f
        override val saverJ2Across: Float = 54.3f
        override val saverJ3Across: Float = 11.5f
        override val saverJ4Across: Float = -63.8f
        override val saverJ5Across: Float = 25.2f
        override val saverJ6Across: Float = 121.8f
        override val saverL1Across: Float = 89.2f
        override val saverL2Across: Float = 118.3f
        override val saverL3Across: Float = -28.7f
        override val saverL4Across: Float = -42.3f
        override val saverL5Across: Float = 121.8f
        override val saverM1Across: Float = 256.3f
        override val saverM2Across: Float = 285.8f
        override val saverM3Across: Float = 362.8f
        override val saverM4Across: Float = 138.4f
        override val saverM5Across: Float = 124.8f
        override val saverM6Across: Float = 124.8f
        override val saverN1Across: Float = 289.2f
        override val saverN2Across: Float = 318.7f
        override val saverN3Across: Float = 332.3f
        override val saverN4Across: Float = 105.5f
        override val saverN5Across: Float = 2.5f
        override val saverN6Across: Float = 37.5f
        override val saverQ1Across: Float = 379.2f
        override val saverQ2Across: Float = 146.4f
        override val saverQ3Across: Float = 116.9f
        override val saverQ4Across: Float = 261.3f
        override val saverQ5Across: Float = 2.5f
        override val saverQ6Across: Float = 37.5f
        override val saverQ7Across: Float = 261.3f
        override val settings_filterCount: Float = 5f
        override val settings_maxVolumeSteps: String = "5,10,15,20,25,30,35,40,45,50,55,60,65,70,75,80,85,90,95,100"
        override val settings_powerOnSteps: String = "0,1,5,10,15,20,25,30,35,40,45,50,55,60,65,70,75,80,85,90,95,100"
        override val settings_brightnessDefault: Float = 35f
        override val homeRingX: Float = 25.7f
        override val homeRingY: Float = 91.6f
        override val homeRingSize: Float = 35f
        override val homeRingStroke: Float = 4f
        override val homeRingHitBottom: Float = 115.9f
        override val npSeekBandX0: Float = 6.1f
        override val npSeekBandX1: Float = 171.5f
        override val npSeekBandBottom: Float = 291.89f
        override val npLyricBottom: Float = 336f
        override val lockLyricBaseline: Float = 304f
        override val large_npLyricPitch: Float = 38.7f
        override val large_npNextPitch: Float = 21.5f
        override val large_npAlbumPitch: Float = 26.5f
        override val npNextCountLarge: Float = 2f
        override val npNextNavCountLarge: Float = 1f
    }

    fun of(board: String): Board = when (board) {
        "ap80" -> ap80
        "v1" -> v1
        "r1" -> r1
        else -> throw IllegalArgumentException("board $board")
    }
}
