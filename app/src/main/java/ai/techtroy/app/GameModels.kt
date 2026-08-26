package ai.techtroy.app

import android.graphics.Color

internal enum class GamePhase {
    TITLE,
    DIG,
    BUILD,
    WAVE,
    PAUSED,
    VICTORY,
    GAME_OVER
}

internal enum class BuildTool(val title: String, val cost: Int) {
    DIG("DIG", 0),
    BOLT("BOLT", 70),
    FROST("FROST", 90),
    CANNON("CANNON", 125),
    SPIKES("SPIKES", 45)
}

internal enum class TowerKind(
    val title: String,
    val cost: Int,
    val range: Float,
    val damage: Float,
    val interval: Float,
    val projectileSpeed: Float,
    val accent: Int
) {
    BOLT("Bolt Tower", 70, 2.8f, 24f, 0.62f, 8.5f, Color.rgb(190, 244, 78)),
    FROST("Frost Prism", 90, 2.55f, 13f, 0.88f, 7.2f, Color.rgb(93, 220, 255)),
    CANNON("Core Cannon", 125, 2.45f, 46f, 1.38f, 5.2f, Color.rgb(255, 164, 75))
}

internal enum class EnemyKind(
    val title: String,
    val maxHealth: Float,
    val speed: Float,
    val bounty: Int,
    val baseDamage: Int,
    val color: Int,
    val scale: Float
) {
    MOSSER("Mosser", 74f, 0.82f, 13, 1, Color.rgb(91, 178, 93), 0.72f),
    RUNNER("Runner", 62f, 1.28f, 15, 1, Color.rgb(242, 179, 72), 0.62f),
    BRUTE("Brute", 220f, 0.56f, 28, 2, Color.rgb(137, 104, 179), 0.92f),
    OVERGROWTH("The Overgrowth", 980f, 0.40f, 180, 4, Color.rgb(214, 76, 88), 1.28f)
}

internal data class GridCell(val col: Int, val row: Int)

internal class Tower(
    val col: Int,
    val row: Int,
    val kind: TowerKind
) {
    var level = 1
    var cooldown = 0f
    var angle = -1.5708f
    var recoil = 0f

    fun upgradeCost(): Int {
        return kind.cost / 2 + level * 25
    }

    fun sellValue(): Int {
        return (kind.cost * 0.65f + (level - 1) * 24f).toInt()
    }

    fun currentDamage(): Float {
        return kind.damage * (1f + (level - 1) * 0.38f)
    }

    fun currentRange(): Float {
        return kind.range + (level - 1) * 0.18f
    }

    fun currentInterval(): Float {
        return kind.interval / (1f + (level - 1) * 0.16f)
    }
}

internal class SpikeTrap(
    val id: Int,
    val col: Int,
    val row: Int
) {
    var pulse = 0f
}

internal class Enemy(
    val id: Int,
    val kind: EnemyKind
) {
    var health = kind.maxHealth
    var progress = 0f
    var x = 0f
    var y = 0f
    var slowTimer = 0f
    var flashTimer = 0f
    var animation = 0f
    var alive = true
    var rewarded = false
    val triggeredTraps = HashSet<Int>()
}

internal class Projectile(
    var x: Float,
    var y: Float,
    val target: Enemy,
    val kind: TowerKind,
    val damage: Float,
    val speed: Float
) {
    var alive = true
    var age = 0f
}

internal class Particle(
    var x: Float,
    var y: Float,
    var velocityX: Float,
    var velocityY: Float,
    var life: Float,
    val maxLife: Float,
    val color: Int,
    val size: Float,
    val square: Boolean
)

internal class FloatingLabel(
    val message: String,
    var x: Float,
    var y: Float,
    val color: Int,
    var life: Float = 1.1f
)
