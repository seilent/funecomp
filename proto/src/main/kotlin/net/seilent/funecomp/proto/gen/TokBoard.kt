package net.seilent.funecomp.proto.gen

object TokBoard {
    class Board(
        val u: Float,
        val minTextPx: Float,
        val settings_filterCount: Float,
        val settings_maxVolumeSteps: String,
        val settings_powerOnSteps: String,
        val settings_brightnessDefault: Float,
    )

    val ap80 = Board(
        u = 1.5f,
        minTextPx = 12f,
        settings_filterCount = 8f,
        settings_maxVolumeSteps = "50,55,60,65,70,75,80,85,90,95,100",
        settings_powerOnSteps = "0,10,15,20,25,30,35,40,45,50,55,60,65,70,75,80,85,90,95,100",
        settings_brightnessDefault = 55f,
    )

    val v1 = Board(
        u = 1.0f,
        minTextPx = 8f,
        settings_filterCount = 5f,
        settings_maxVolumeSteps = "5,10,15,20,25,30,35,40,45,50,55,60,65,70,75,80,85,90,95,100",
        settings_powerOnSteps = "0,1,5,10,15,20,25,30,35,40,45,50,55,60,65,70,75,80,85,90,95,100",
        settings_brightnessDefault = 35f,
    )

    fun of(board: String): Board = when (board) {
        "ap80" -> ap80
        "v1" -> v1
        else -> throw IllegalArgumentException("board $board")
    }
}
