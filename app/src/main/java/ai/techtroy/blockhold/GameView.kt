package ai.techtroy.blockhold

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import java.util.Random
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

internal class GameView(context: Context) : SurfaceView(context), SurfaceHolder.Callback, Runnable {

    companion object {
        private const val COLS = 12
        private const val ROWS = 7
        private const val START_ROW = 3
        private const val MAX_PATH_LENGTH = 46
        private const val STARTING_BLOCKS = 360
        private const val STARTING_CORE = 12
    }

    private val stateLock = Any()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val spritePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val boldTypeface = Typeface.create("sans-serif", Typeface.BOLD)
    private val blackTypeface = Typeface.create("sans-serif-black", Typeface.NORMAL)
    private val regularTypeface = Typeface.create("sans-serif", Typeface.NORMAL)
    private val random = Random(7331L)
    private val audio = AudioEngine(context)
    private val sprites = SpriteCatalog(context)
    private val preferences = context.getSharedPreferences("blockhold_infinite_progress", Context.MODE_PRIVATE)

    @Volatile private var running = false
    @Volatile private var activityPaused = false
    private var renderThread: Thread? = null

    private var phase = GamePhase.TITLE
    private var phaseBeforePause = GamePhase.BUILD
    private var selectedTool = BuildTool.DIG
    private var selectedTower: Tower? = null
    private var selectedTrap: SpikeTrap? = null
    private var buildPage = BuildPage.TOWERS

    private val pathCells = ArrayList<GridCell>()
    private val towers = ArrayList<Tower>()
    private val traps = ArrayList<SpikeTrap>()
    private val enemies = ArrayList<Enemy>()
    private val pendingSpawns = ArrayList<Enemy>()
    private val projectiles = ArrayList<Projectile>()
    private val particles = ArrayList<Particle>()
    private val floatingLabels = ArrayList<FloatingLabel>()
    private val waveQueue = ArrayList<SpawnSpec>()

    private var gold = STARTING_BLOCKS
    private var lives = STARTING_CORE
    private var score = 0
    private var bestScore = preferences.getInt("best_score", 0)
    private var bestWave = preferences.getInt("best_wave", 0)
    private var waveNumber = 0
    private var spawnIndex = 0
    private var spawnTimer = 0f
    private var nextEnemyId = 1
    private var nextTrapId = 1
    private var pathComplete = false
    private var ambientTime = 0f
    private var bannerText = ""
    private var bannerTimer = 0f
    private var screenShake = 0f
    private var diggingGesture = false
    private var waveTheme = "FORGE THE FIRST ROUTE"
    private var savedRunAvailable = preferences.getBoolean("has_saved_run", false)

    private var viewWidth = 1f
    private var viewHeight = 1f
    private var density = resources.displayMetrics.density
    private var topBarHeight = 1f
    private var bottomBarHeight = 1f
    private var boardLeft = 0f
    private var boardTop = 0f
    private var tileSize = 1f

    private val primaryActionRect = RectF()
    private val resetPathRect = RectF()
    private val pauseRect = RectF()
    private val soundRect = RectF()
    private val titlePlayRect = RectF()
    private val titleContinueRect = RectF()
    private val titleSoundRect = RectF()
    private val endPrimaryRect = RectF()
    private val endSecondaryRect = RectF()
    private val upgradeRect = RectF()
    private val sellRect = RectF()
    private val backRect = RectF()
    private val towerPageRect = RectF()
    private val trapPageRect = RectF()
    private val toolRects = ArrayList<Pair<BuildTool, RectF>>()

    init {
        holder.addCallback(this)
        isFocusable = true
        keepScreenOn = true
        strokePaint.style = Paint.Style.STROKE
        pathCells.add(GridCell(0, START_ROW))
    }

    override fun surfaceCreated(surfaceHolder: SurfaceHolder) {
        startRenderer()
    }

    override fun surfaceChanged(surfaceHolder: SurfaceHolder, format: Int, width: Int, height: Int) {
        synchronized(stateLock) {
            computeLayout(width.toFloat(), height.toFloat())
        }
    }

    override fun surfaceDestroyed(surfaceHolder: SurfaceHolder) {
        stopRenderer()
    }

    private fun startRenderer() {
        if (running) return
        running = true
        renderThread = Thread(this, "BlockholdInfiniteLoop")
        renderThread?.start()
    }

    private fun stopRenderer() {
        running = false
        val thread = renderThread
        if (thread != null) {
            try {
                thread.join(700L)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        renderThread = null
    }

    override fun run() {
        var previousTime = System.nanoTime()
        while (running) {
            val frameStart = System.nanoTime()
            var delta = (frameStart - previousTime) / 1_000_000_000f
            previousTime = frameStart
            if (delta > 0.04f) delta = 0.04f

            if (!activityPaused && holder.surface.isValid) {
                var canvas: Canvas? = null
                try {
                    canvas = holder.lockCanvas()
                    if (canvas != null) {
                        synchronized(stateLock) {
                            if (viewWidth <= 1f || viewHeight <= 1f) {
                                computeLayout(canvas.width.toFloat(), canvas.height.toFloat())
                            }
                            update(delta)
                            drawFrame(canvas)
                        }
                    }
                } finally {
                    if (canvas != null) holder.unlockCanvasAndPost(canvas)
                }
            }

            val elapsedMs = (System.nanoTime() - frameStart) / 1_000_000L
            val sleepMs = 16L - elapsedMs
            if (sleepMs > 1L) SystemClock.sleep(sleepMs)
        }
    }

    fun pauseFromActivity() {
        synchronized(stateLock) {
            if (phase == GamePhase.WAVE || phase == GamePhase.BUILD || phase == GamePhase.DIG) {
                phaseBeforePause = phase
                phase = GamePhase.PAUSED
            }
            if (phaseBeforePause == GamePhase.BUILD && pathComplete) saveRun()
            activityPaused = true
        }
    }

    fun resumeFromActivity() {
        activityPaused = false
    }

    fun release() {
        stopRenderer()
        audio.release()
    }

    fun handleBackPressed(): Boolean {
        synchronized(stateLock) {
            when (phase) {
                GamePhase.TITLE -> return false
                GamePhase.PAUSED, GamePhase.VICTORY, GamePhase.GAME_OVER -> returnToTitle()
                else -> pauseGame()
            }
            return true
        }
    }

    private fun computeLayout(width: Float, height: Float) {
        viewWidth = max(1f, width)
        viewHeight = max(1f, height)
        density = resources.displayMetrics.density

        topBarHeight = min(viewHeight * 0.145f, dp(68f))
        bottomBarHeight = min(viewHeight * 0.235f, dp(112f))
        val verticalSpace = viewHeight - topBarHeight - bottomBarHeight - dp(14f)
        tileSize = min((viewWidth - dp(28f)) / COLS, verticalSpace / ROWS)
        tileSize = max(20f, tileSize)
        boardLeft = (viewWidth - tileSize * COLS) * 0.5f
        boardTop = topBarHeight + (verticalSpace - tileSize * ROWS) * 0.5f + dp(5f)

        val smallButton = min(dp(44f), topBarHeight - dp(14f))
        val top = dp(7f)
        val bottom = topBarHeight - dp(7f)
        pauseRect.set(viewWidth - dp(8f) - smallButton, top, viewWidth - dp(8f), bottom)
        soundRect.set(pauseRect.left - dp(7f) - smallButton, top, pauseRect.left - dp(7f), bottom)
        val actionWidth = min(dp(130f), viewWidth * 0.17f)
        primaryActionRect.set(soundRect.left - dp(8f) - actionWidth, top, soundRect.left - dp(8f), bottom)
        val resetWidth = min(dp(96f), viewWidth * 0.13f)
        resetPathRect.set(primaryActionRect.left - dp(7f) - resetWidth, top, primaryActionRect.left - dp(7f), bottom)

        val toolTop = viewHeight - bottomBarHeight + dp(9f)
        val toolBottom = viewHeight - dp(9f)
        val totalWidth = min(viewWidth - dp(20f), dp(790f))
        val left = (viewWidth - totalWidth) * 0.5f
        val pageWidth = min(dp(82f), totalWidth * 0.13f)
        val pageGap = dp(5f)
        towerPageRect.set(left, toolTop, left + pageWidth, (toolTop + toolBottom - pageGap) * 0.5f)
        trapPageRect.set(left, towerPageRect.bottom + pageGap, left + pageWidth, toolBottom)
        rebuildToolRects(left + pageWidth + dp(7f), totalWidth - pageWidth - dp(7f), toolTop, toolBottom)

        val panelWidth = min(viewWidth - dp(24f), dp(650f))
        val panelLeft = (viewWidth - panelWidth) * 0.5f
        val panelTop = viewHeight - bottomBarHeight + dp(12f)
        val panelBottom = viewHeight - dp(12f)
        val panelGap = dp(8f)
        backRect.set(panelLeft, panelTop, panelLeft + panelWidth * 0.17f, panelBottom)
        upgradeRect.set(backRect.right + panelGap, panelTop, backRect.right + panelGap + panelWidth * 0.50f, panelBottom)
        sellRect.set(upgradeRect.right + panelGap, panelTop, panelLeft + panelWidth, panelBottom)

        val titleButtonWidth = min(dp(230f), viewWidth * 0.34f)
        val titleButtonHeight = min(dp(60f), viewHeight * 0.13f)
        titlePlayRect.set(viewWidth * 0.5f - titleButtonWidth - dp(6f), viewHeight * 0.70f, viewWidth * 0.5f - dp(6f), viewHeight * 0.70f + titleButtonHeight)
        titleContinueRect.set(viewWidth * 0.5f + dp(6f), viewHeight * 0.70f, viewWidth * 0.5f + titleButtonWidth + dp(6f), viewHeight * 0.70f + titleButtonHeight)
        titleSoundRect.set(viewWidth - dp(58f), dp(14f), viewWidth - dp(14f), dp(58f))

        val endButtonWidth = min(dp(230f), viewWidth * 0.34f)
        val endButtonHeight = min(dp(58f), viewHeight * 0.13f)
        endPrimaryRect.set(viewWidth * 0.5f - endButtonWidth - dp(6f), viewHeight * 0.70f, viewWidth * 0.5f - dp(6f), viewHeight * 0.70f + endButtonHeight)
        endSecondaryRect.set(viewWidth * 0.5f + dp(6f), viewHeight * 0.70f, viewWidth * 0.5f + endButtonWidth + dp(6f), viewHeight * 0.70f + endButtonHeight)
    }

    private fun rebuildToolRects(left: Float = towerPageRect.right + dp(7f), width: Float = min(viewWidth - dp(20f), dp(790f)) - towerPageRect.width() - dp(7f), top: Float = viewHeight - bottomBarHeight + dp(9f), bottom: Float = viewHeight - dp(9f)) {
        toolRects.clear()
        val tools = visibleTools()
        val gap = dp(6f)
        val buttonWidth = (width - gap * (tools.size - 1)) / tools.size
        var x = left
        for (tool in tools) {
            toolRects.add(Pair(tool, RectF(x, top, x + buttonWidth, bottom)))
            x += buttonWidth + gap
        }
    }

    private fun visibleTools(): Array<BuildTool> {
        return if (buildPage == BuildPage.TOWERS) {
            arrayOf(BuildTool.BOLT, BuildTool.FROST, BuildTool.CANNON, BuildTool.EMBER, BuildTool.BEACON)
        } else {
            arrayOf(BuildTool.SPIKES, BuildTool.ROOT, BuildTool.RUNE, BuildTool.ARC, BuildTool.CRUSHER)
        }
    }

    private fun update(delta: Float) {
        ambientTime += delta
        if (bannerTimer > 0f) bannerTimer -= delta
        if (screenShake > 0f) screenShake -= delta
        if (phase == GamePhase.WAVE) updateWave(delta)
        updateEffects(delta)
    }

    private fun updateWave(delta: Float) {
        if (spawnIndex < waveQueue.size) {
            spawnTimer -= delta
            if (spawnTimer <= 0f) {
                spawnEnemy(waveQueue[spawnIndex])
                spawnIndex += 1
                val rush = waveNumber % 8 == 2 || waveNumber % 8 == 1
                spawnTimer = if (rush) 0.48f else max(0.46f, 0.82f - waveNumber * 0.008f)
            }
        }

        for (enemy in enemies) {
            if (!enemy.alive) continue
            enemy.animation += delta * (4f + enemy.moveSpeed * 2f)
            enemy.flashTimer = max(0f, enemy.flashTimer - delta)
            enemy.slowTimer = max(0f, enemy.slowTimer - delta)
            enemy.stunTimer = max(0f, enemy.stunTimer - delta)
            enemy.abilityTimer -= delta

            if (enemy.burnTimer > 0f) {
                enemy.burnTimer -= delta
                damageEnemy(enemy, enemy.burnDamagePerSecond * delta, Color.rgb(255, 104, 55), 0.45f, false)
            }
            val regeneration = enemy.kind.regeneration + if (waveNumber % 8 == 4) 0.006f else 0f
            if (regeneration > 0f && enemy.health > 0f && enemy.health < enemy.maxHealth) {
                enemy.health = min(enemy.maxHealth, enemy.health + enemy.maxHealth * regeneration * delta)
            }
            if (!enemy.alive) continue
            updateEnemyAbility(enemy)

            val slowMultiplier = if (enemy.slowTimer > 0f) 0.56f else 1f
            if (enemy.stunTimer <= 0f) enemy.progress += enemy.moveSpeed * slowMultiplier * delta
            updateEnemyPosition(enemy)
            triggerTrapIfNeeded(enemy)
            if (!enemy.alive) continue

            if (enemy.progress >= pathCells.size - 1f) {
                enemy.alive = false
                lives = max(0, lives - enemy.kind.baseDamage - if (enemy.bossTier >= 3) 1 else 0)
                audio.play("base_hit", 0.75f, if (enemy.kind.boss) 0.72f else 1f)
                screenShake = 0.35f
                burst(enemy.x, enemy.y, Color.rgb(255, 91, 84), 18, 1.4f)
                floatingLabels.add(FloatingLabel("-${enemy.kind.baseDamage} CORE", enemy.x, enemy.y, Color.rgb(255, 107, 96)))
                if (lives <= 0) {
                    finishRun(false)
                    return
                }
            }
        }

        for (tower in towers) {
            tower.cooldown -= delta
            tower.recoil = max(0f, tower.recoil - delta * 4.2f)
            tower.disabledTimer = max(0f, tower.disabledTimer - delta)
            if (tower.disabledTimer > 0f) continue
            val target = findTarget(tower)
            if (target != null) {
                tower.angle = atan2(target.y - (tower.row + 0.5f), target.x - (tower.col + 0.5f))
                if (tower.cooldown <= 0f) {
                    fireTower(tower, target)
                    tower.cooldown = tower.currentInterval()
                    tower.recoil = 1f
                }
            }
        }

        updateProjectiles(delta)
        if (pendingSpawns.isNotEmpty()) {
            enemies.addAll(pendingSpawns)
            pendingSpawns.clear()
        }
        removeDefeatedEnemies()

        if (spawnIndex >= waveQueue.size && enemies.isEmpty() && projectiles.isEmpty() && phase == GamePhase.WAVE) completeWave()
    }

    private fun updateEnemyAbility(enemy: Enemy) {
        if (enemy.abilityTimer > 0f) return
        when (enemy.kind) {
            EnemyKind.BLINK_STALKER -> {
                enemy.progress = min(pathCells.size - 1.05f, enemy.progress + 0.85f)
                enemy.abilityTimer = 4.3f
                burst(enemy.x, enemy.y, enemy.kind.color, 10, 0.8f)
            }
            EnemyKind.ROOTCALLER -> {
                for (ally in enemies) {
                    if (!ally.alive) continue
                    val dx = ally.x - enemy.x
                    val dy = ally.y - enemy.y
                    if (dx * dx + dy * dy <= 4.4f) ally.health = min(ally.maxHealth, ally.health + ally.maxHealth * 0.055f)
                }
                enemy.abilityTimer = 4.8f
                burst(enemy.x, enemy.y, Color.rgb(91, 196, 99), 12, 0.75f)
            }
            EnemyKind.HEX_WEAVER -> {
                val target = towers.filter { it.disabledTimer <= 0f }.minBy {
                    val dx = it.col + 0.5f - enemy.x
                    val dy = it.row + 0.5f - enemy.y
                    dx * dx + dy * dy
                }
                if (target != null) {
                    val dx = target.col + 0.5f - enemy.x
                    val dy = target.row + 0.5f - enemy.y
                    if (dx * dx + dy * dy <= 10f) {
                        target.disabledTimer = 2.8f
                        floatingLabels.add(FloatingLabel("HEXED", target.col + 0.5f, target.row + 0.35f, enemy.kind.color))
                    }
                }
                enemy.abilityTimer = 5.7f
            }
            EnemyKind.OVERGROWTH -> {
                enemy.health = min(enemy.maxHealth, enemy.health + enemy.maxHealth * (0.025f + min(0.025f, enemy.bossTier * 0.003f)))
                if (enemy.bossTier >= 2) {
                    repeat(min(3, 1 + enemy.bossTier / 3)) {
                        val minion = Enemy(nextEnemyId++, EnemyKind.SPLITLING, enemy.healthScale * 0.11f, min(1.55f, enemy.speedScale * 1.18f), 0.45f, splitDepth = 1)
                        minion.progress = max(0f, enemy.progress - it * 0.18f)
                        updateEnemyPosition(minion)
                        pendingSpawns.add(minion)
                    }
                }
                if (enemy.bossTier >= 3) {
                    towers.sortedBy {
                        val dx = it.col + 0.5f - enemy.x
                        val dy = it.row + 0.5f - enemy.y
                        dx * dx + dy * dy
                    }.take(min(3, enemy.bossTier - 1)).forEach { it.disabledTimer = 2.2f }
                }
                enemy.abilityTimer = max(3.4f, 6.8f - enemy.bossTier * 0.25f)
                burst(enemy.x, enemy.y, Color.rgb(103, 220, 94), 18, 1.1f)
            }
            else -> enemy.abilityTimer = 8f
        }
    }

    private fun spawnEnemy(spec: SpawnSpec) {
        if (pathCells.size < 2) return
        val enemy = Enemy(nextEnemyId++, spec.kind, spec.healthScale, spec.speedScale, spec.rewardScale, spec.bossTier, spec.splitDepth)
        updateEnemyPosition(enemy)
        enemies.add(enemy)
    }

    private fun updateEnemyPosition(enemy: Enemy) {
        val lastIndex = pathCells.size - 1
        if (lastIndex <= 0) return
        val clampedProgress = min(enemy.progress, lastIndex.toFloat())
        val index = min(floor(clampedProgress).toInt(), lastIndex - 1)
        val fraction = clampedProgress - index
        val first = pathCells[index]
        val second = pathCells[min(index + 1, lastIndex)]
        enemy.x = first.col + 0.5f + (second.col - first.col) * fraction
        enemy.y = first.row + 0.5f + (second.row - first.row) * fraction
    }

    private fun triggerTrapIfNeeded(enemy: Enemy) {
        val pathIndex = min(max(0, (enemy.progress + 0.25f).toInt()), pathCells.size - 1)
        val cell = pathCells[pathIndex]
        for (trap in traps) {
            if (trap.col != cell.col || trap.row != cell.row || enemy.triggeredTraps.contains(trap.id)) continue
            enemy.triggeredTraps.add(trap.id)
            trap.pulse = 1f
            when (trap.kind) {
                TrapKind.SPIKE -> damageEnemy(enemy, trap.currentDamage(), trap.kind.accent)
                TrapKind.ROOT -> {
                    damageEnemy(enemy, trap.currentDamage(), trap.kind.accent)
                    enemy.slowTimer = max(enemy.slowTimer, 2.2f + trap.level * 0.25f)
                }
                TrapKind.EMBER -> {
                    damageEnemy(enemy, trap.currentDamage(), trap.kind.accent, 0.35f)
                    enemy.burnTimer = max(enemy.burnTimer, 3.5f)
                    enemy.burnDamagePerSecond = max(enemy.burnDamagePerSecond, trap.currentDamage() * 0.30f)
                }
                TrapKind.ARC -> {
                    damageEnemy(enemy, trap.currentDamage(), trap.kind.accent, 0.55f)
                    enemy.stunTimer = max(enemy.stunTimer, 0.55f + trap.level * 0.10f)
                    enemies.filter { it.alive && it !== enemy && abs(it.progress - enemy.progress) < 1.4f }.take(2).forEach {
                        damageEnemy(it, trap.currentDamage() * 0.55f, trap.kind.accent, 0.65f)
                    }
                }
                TrapKind.CRUSHER -> {
                    damageEnemy(enemy, trap.currentDamage(), trap.kind.accent)
                    enemy.stunTimer = max(enemy.stunTimer, 1.0f)
                }
            }
            burst(enemy.x, enemy.y, trap.kind.accent, if (trap.kind == TrapKind.CRUSHER) 15 else 9, 0.9f)
            audio.play("dig", 0.32f, 1.18f + trap.kind.ordinal * 0.04f)
        }
    }

    private fun findTarget(tower: Tower): Enemy? {
        var best: Enemy? = null
        var bestProgress = -1f
        val centerX = tower.col + 0.5f
        val centerY = tower.row + 0.5f
        val rangeSquared = tower.currentRange() * tower.currentRange()
        for (enemy in enemies) {
            if (!enemy.alive) continue
            val dx = enemy.x - centerX
            val dy = enemy.y - centerY
            if (dx * dx + dy * dy <= rangeSquared && enemy.progress > bestProgress) {
                best = enemy
                bestProgress = enemy.progress
            }
        }
        return best
    }

    private fun fireTower(tower: Tower, target: Enemy) {
        projectiles.add(Projectile(tower.col + 0.5f, tower.row + 0.5f, target, tower.kind, tower.currentDamage(), tower.kind.projectileSpeed))
        when (tower.kind) {
            TowerKind.BOLT -> audio.play("bolt", 0.22f, 0.94f + random.nextFloat() * 0.12f)
            TowerKind.FROST -> audio.play("frost", 0.20f, 0.96f + random.nextFloat() * 0.10f)
            TowerKind.CANNON -> {
                audio.play("cannon", 0.42f, 0.92f + random.nextFloat() * 0.08f)
                screenShake = max(screenShake, 0.09f)
            }
            TowerKind.EMBER -> audio.play("bolt", 0.27f, 0.72f + random.nextFloat() * 0.08f)
            TowerKind.BEACON -> audio.play("frost", 0.26f, 1.25f + random.nextFloat() * 0.08f)
        }
    }

    private fun updateProjectiles(delta: Float) {
        for (projectile in projectiles) {
            if (!projectile.alive) continue
            projectile.age += delta
            if (!projectile.target.alive) {
                projectile.alive = false
                continue
            }
            val dx = projectile.target.x - projectile.x
            val dy = projectile.target.y - projectile.y
            val distance = sqrt(dx * dx + dy * dy)
            val movement = projectile.speed * delta
            if (distance <= movement + 0.10f) {
                projectile.x = projectile.target.x
                projectile.y = projectile.target.y
                impactProjectile(projectile)
                projectile.alive = false
            } else if (distance > 0f) {
                projectile.x += dx / distance * movement
                projectile.y += dy / distance * movement
            }
        }
        projectiles.removeAll { !it.alive }
    }

    private fun impactProjectile(projectile: Projectile) {
        when (projectile.kind) {
            TowerKind.BOLT -> {
                damageEnemy(projectile.target, projectile.damage, projectile.kind.accent)
                burst(projectile.x, projectile.y, projectile.kind.accent, 5, 0.55f)
            }
            TowerKind.FROST -> {
                damageEnemy(projectile.target, projectile.damage, projectile.kind.accent, 0.75f)
                projectile.target.slowTimer = max(projectile.target.slowTimer, 1.9f)
                burst(projectile.x, projectile.y, projectile.kind.accent, 8, 0.7f)
            }
            TowerKind.CANNON, TowerKind.EMBER -> {
                val radius = if (projectile.kind == TowerKind.CANNON) 0.9f else 0.72f
                for (enemy in enemies) {
                    if (!enemy.alive) continue
                    val dx = enemy.x - projectile.x
                    val dy = enemy.y - projectile.y
                    val distance = sqrt(dx * dx + dy * dy)
                    if (distance <= radius) {
                        val multiplier = 1f - distance * 0.32f
                        damageEnemy(enemy, projectile.damage * multiplier, projectile.kind.accent, if (projectile.kind == TowerKind.EMBER) 0.45f else 0f)
                        if (projectile.kind == TowerKind.EMBER) {
                            enemy.burnTimer = max(enemy.burnTimer, 2.8f)
                            enemy.burnDamagePerSecond = max(enemy.burnDamagePerSecond, projectile.damage * 0.18f)
                        }
                    }
                }
                burst(projectile.x, projectile.y, projectile.kind.accent, if (projectile.kind == TowerKind.CANNON) 18 else 13, 1.4f)
                screenShake = max(screenShake, if (projectile.kind == TowerKind.CANNON) 0.18f else 0.08f)
            }
            TowerKind.BEACON -> {
                val chain = enemies.filter { it.alive && abs(it.progress - projectile.target.progress) < 2.1f }.sortedBy { abs(it.progress - projectile.target.progress) }.take(4)
                chain.forEachIndexed { index, enemy ->
                    damageEnemy(enemy, projectile.damage * (1f - index * 0.14f), projectile.kind.accent, 0.70f)
                    burst(enemy.x, enemy.y, projectile.kind.accent, 5, 0.55f)
                }
            }
        }
    }

    private fun damageEnemy(enemy: Enemy, amount: Float, effectColor: Int, armorPierce: Float = 0f, showLabel: Boolean = true) {
        if (!enemy.alive) return
        val armor = enemy.kind.armor * (1f - armorPierce)
        val actual = max(0.5f, amount * (1f - armor))
        enemy.health -= actual
        enemy.flashTimer = 0.11f
        if (showLabel && random.nextFloat() < 0.22f) floatingLabels.add(FloatingLabel(actual.toInt().toString(), enemy.x, enemy.y - 0.25f, effectColor, 0.7f))
        if (enemy.health > 0f) return
        enemy.alive = false
        if (!enemy.rewarded) {
            enemy.rewarded = true
            gold = safeAdd(gold, enemy.bounty)
            score = safeAdd(score, enemy.bounty * 10)
            floatingLabels.add(FloatingLabel("+${enemy.bounty}", enemy.x, enemy.y, Color.rgb(190, 244, 78)))
            burst(enemy.x, enemy.y, enemy.kind.color, if (enemy.kind.boss) 34 else if (enemy.kind.elite) 20 else 12, 1.2f)
            audio.play("enemy_down", if (enemy.kind.boss) 0.65f else 0.22f, if (enemy.kind.boss) 0.65f else 1.05f)
            if (enemy.kind == EnemyKind.SPLITLING && enemy.splitDepth < 1) {
                repeat(2) {
                    val child = Enemy(nextEnemyId++, EnemyKind.MOSSER, enemy.healthScale * 0.34f, min(1.7f, enemy.speedScale * 1.3f), 0.32f, splitDepth = 1)
                    child.progress = max(0f, enemy.progress - it * 0.12f)
                    updateEnemyPosition(child)
                    pendingSpawns.add(child)
                }
            }
        }
    }

    private fun removeDefeatedEnemies() {
        enemies.removeAll { !it.alive }
    }

    private fun updateEffects(delta: Float) {
        for (trap in traps) trap.pulse = max(0f, trap.pulse - delta * 3.2f)
        for (particle in particles) {
            particle.life -= delta
            particle.x += particle.velocityX * delta
            particle.y += particle.velocityY * delta
            particle.velocityY += 0.45f * delta
            particle.velocityX *= 0.985f
        }
        particles.removeAll { it.life <= 0f }
        for (label in floatingLabels) {
            label.life -= delta
            label.y -= delta * 0.38f
        }
        floatingLabels.removeAll { it.life <= 0f }
    }

    private fun completeWave() {
        phase = GamePhase.BUILD
        val reward = min(250_000L, 45L + waveNumber.toLong() * 13L).toInt()
        gold = safeAdd(gold, reward)
        score = safeAdd(score, reward * 5)
        if (waveNumber % 10 == 0 && lives < STARTING_CORE) {
            lives += 1
            setBanner("MUTATED BOSS BROKEN  CORE +1  +$reward BLOCKS", 3.0f)
        } else {
            setBanner("WAVE $waveNumber CLEARED  +$reward BLOCKS", 2.4f)
        }
        selectedTower = null
        selectedTrap = null
        updateRecords()
        saveRun()
        audio.play("build", 0.45f, 1.14f)
    }

    private fun finishRun(victory: Boolean) {
        phase = if (victory) GamePhase.VICTORY else GamePhase.GAME_OVER
        selectedTower = null
        selectedTrap = null
        updateRecords()
        clearSavedRun()
    }

    private fun updateRecords() {
        var changed = false
        if (score > bestScore) {
            bestScore = score
            changed = true
        }
        if (waveNumber > bestWave) {
            bestWave = waveNumber
            changed = true
        }
        if (changed) preferences.edit().putInt("best_score", bestScore).putInt("best_wave", bestWave).apply()
    }

    private fun startWave() {
        if (phase != GamePhase.BUILD || !pathComplete) return
        saveRun()
        waveNumber += 1
        waveQueue.clear()
        buildWaveQueue(waveNumber)
        spawnIndex = 0
        spawnTimer = 0.25f
        selectedTower = null
        selectedTrap = null
        phase = GamePhase.WAVE
        val message = when {
            waveNumber % 10 == 0 -> "MUTATED OVERGROWTH  TIER ${waveNumber / 10}"
            waveNumber % 5 == 0 -> "ELITE SIGNAL  $waveTheme"
            else -> "WAVE $waveNumber  $waveTheme"
        }
        setBanner(message, 2.4f)
        audio.play("wave", 0.65f, if (waveNumber % 10 == 0) 0.78f else 1f)
    }

    private fun buildWaveQueue(wave: Int) {
        val healthScale = waveHealthScale(wave)
        val speedScale = min(1.42f, 1f + wave * 0.006f)
        val rewardScale = min(16f, 1f + wave * 0.075f)
        val regularCount = min(34, 6 + wave / 2)
        val regulars = arrayOf(EnemyKind.MOSSER, EnemyKind.RUNNER, EnemyKind.BRUTE, EnemyKind.SHELLBACK, EnemyKind.SPLITLING)
        waveTheme = when (wave % 8) {
            1 -> "SWARM FRONT"
            2 -> "RUSH FRONT"
            3 -> "ARMORED FRONT"
            4 -> "REGENERATION BROOD"
            5 -> "SABOTAGE FRONT"
            6 -> "SIEGE COLUMN"
            7 -> "SPLIT SWARM"
            else -> "MIXED ASSAULT"
        }
        val count = if (wave % 8 == 1) min(40, regularCount + 8) else regularCount
        repeat(count) { index ->
            val kind = when (wave % 8) {
                1 -> if (index % 3 == 0) EnemyKind.SPLITLING else EnemyKind.MOSSER
                2 -> if (index % 4 == 0) EnemyKind.MOSSER else EnemyKind.RUNNER
                3 -> if (index % 3 == 0) EnemyKind.BRUTE else EnemyKind.SHELLBACK
                4 -> if (index % 4 == 0) EnemyKind.SHELLBACK else EnemyKind.MOSSER
                5 -> regulars[(index + wave) % regulars.size]
                6 -> if (index % 3 == 0) EnemyKind.BRUTE else EnemyKind.SHELLBACK
                7 -> if (index % 3 == 0) EnemyKind.RUNNER else EnemyKind.SPLITLING
                else -> regulars[(index * 3 + wave) % regulars.size]
            }
            waveQueue.add(SpawnSpec(kind, healthScale, speedScale * if (kind == EnemyKind.RUNNER) 1.03f else 1f, rewardScale))
        }
        if (wave % 8 == 5 && wave % 5 != 0) {
            waveQueue.add(min(waveQueue.size, waveQueue.size * 2 / 3), SpawnSpec(EnemyKind.HEX_WEAVER, healthScale * 0.34f, speedScale, rewardScale * 0.55f))
        }
        if (wave % 5 == 0) {
            val elites = arrayOf(EnemyKind.IRONHIDE, EnemyKind.BLINK_STALKER, EnemyKind.ROOTCALLER, EnemyKind.HEX_WEAVER, EnemyKind.SIEGE_COLOSSUS)
            val eliteKind = elites[((wave / 5) - 1) % elites.size]
            val insertAt = min(waveQueue.size, waveQueue.size * 2 / 3)
            waveQueue.add(insertAt, SpawnSpec(eliteKind, healthScale * 0.72f, speedScale, rewardScale * 1.1f))
            if (wave >= 30) waveQueue.add(min(waveQueue.size, insertAt + 5), SpawnSpec(elites[((wave / 5) + 1) % elites.size], healthScale * 0.58f, speedScale, rewardScale))
        }
        if (wave % 10 == 0) {
            val tier = wave / 10
            waveQueue.add(SpawnSpec(EnemyKind.OVERGROWTH, healthScale * (0.78f + min(1.2f, tier * 0.05f)), min(1.28f, speedScale), rewardScale * 1.3f, tier))
        }
    }

    private fun waveHealthScale(wave: Int): Float {
        val exponentialWave = min(80, max(0, wave - 1))
        val tail = max(0, wave - 81)
        return min(1_000_000_000f, 1.085.pow(exponentialWave.toDouble()).toFloat() * (1f + min(100_000, tail) * 0.055f))
    }

    private fun safeAdd(value: Int, addition: Int): Int {
        return min(2_000_000_000L, value.toLong() + addition.toLong()).toInt()
    }

    private fun saveRun() {
        if (!pathComplete || phase == GamePhase.GAME_OVER) return
        val pathData = pathCells.joinToString(";") { "${it.col},${it.row}" }
        val towerData = towers.joinToString(";") { "${it.col},${it.row},${it.kind.name},${it.level},${it.overcharge}" }
        val trapData = traps.joinToString(";") { "${it.id},${it.col},${it.row},${it.kind.name},${it.level},${it.overcharge}" }
        preferences.edit()
            .putBoolean("has_saved_run", true)
            .putString("run_path", pathData)
            .putString("run_towers", towerData)
            .putString("run_traps", trapData)
            .putInt("run_gold", gold)
            .putInt("run_lives", lives)
            .putInt("run_score", score)
            .putInt("run_wave", waveNumber)
            .apply()
        savedRunAvailable = true
    }

    private fun loadSavedRun() {
        if (!preferences.getBoolean("has_saved_run", false)) return
        try {
            val loadedPath = preferences.getString("run_path", "").orEmpty().split(';').filter { it.isNotBlank() }.map {
                val values = it.split(',')
                GridCell(values[0].toInt(), values[1].toInt())
            }
            if (loadedPath.size < 2 || loadedPath.first() != GridCell(0, START_ROW) || loadedPath.last() != GridCell(COLS - 1, START_ROW)) throw IllegalStateException("Invalid path")
            pathCells.clear()
            pathCells.addAll(loadedPath)
            towers.clear()
            preferences.getString("run_towers", "").orEmpty().split(';').filter { it.isNotBlank() }.forEach {
                val values = it.split(',')
                val tower = Tower(values[0].toInt(), values[1].toInt(), TowerKind.valueOf(values[2]))
                tower.level = values[3].toInt().coerceIn(1, 3)
                tower.overcharge = values[4].toInt().coerceIn(0, 999)
                towers.add(tower)
            }
            traps.clear()
            preferences.getString("run_traps", "").orEmpty().split(';').filter { it.isNotBlank() }.forEach {
                val values = it.split(',')
                val trap = SpikeTrap(values[0].toInt(), values[1].toInt(), values[2].toInt(), TrapKind.valueOf(values[3]))
                trap.level = values[4].toInt().coerceIn(1, 3)
                trap.overcharge = values[5].toInt().coerceIn(0, 999)
                traps.add(trap)
            }
            gold = preferences.getInt("run_gold", STARTING_BLOCKS).coerceAtLeast(0)
            lives = preferences.getInt("run_lives", STARTING_CORE).coerceIn(1, STARTING_CORE)
            score = preferences.getInt("run_score", 0).coerceAtLeast(0)
            waveNumber = preferences.getInt("run_wave", 0).coerceAtLeast(0)
            nextTrapId = (traps.maxBy { it.id }?.id ?: 0) + 1
            nextEnemyId = 1
            enemies.clear()
            pendingSpawns.clear()
            projectiles.clear()
            particles.clear()
            floatingLabels.clear()
            waveQueue.clear()
            pathComplete = true
            selectedTool = BuildTool.BOLT
            selectedTower = null
            selectedTrap = null
            buildPage = BuildPage.TOWERS
            rebuildToolRects()
            phase = GamePhase.BUILD
            setBanner("RUN RESTORED  WAVE ${waveNumber + 1} AWAITS", 3f)
            audio.play("build", 0.50f, 1.05f)
        } catch (_: Exception) {
            clearSavedRun()
            newRun()
            setBanner("SAVE COULD NOT BE RESTORED  NEW RUN STARTED", 2.7f)
        }
    }

    private fun clearSavedRun() {
        preferences.edit().remove("run_path").remove("run_towers").remove("run_traps").remove("run_gold").remove("run_lives").remove("run_score").remove("run_wave").putBoolean("has_saved_run", false).apply()
        savedRunAvailable = false
    }

    private fun newRun() {
        clearSavedRun()
        phase = GamePhase.DIG
        selectedTool = BuildTool.DIG
        selectedTower = null
        selectedTrap = null
        buildPage = BuildPage.TOWERS
        rebuildToolRects()
        pathCells.clear()
        pathCells.add(GridCell(0, START_ROW))
        towers.clear()
        traps.clear()
        enemies.clear()
        pendingSpawns.clear()
        projectiles.clear()
        particles.clear()
        floatingLabels.clear()
        waveQueue.clear()
        gold = STARTING_BLOCKS
        lives = STARTING_CORE
        score = 0
        waveNumber = 0
        nextEnemyId = 1
        nextTrapId = 1
        pathComplete = false
        diggingGesture = false
        waveTheme = "FORGE THE FIRST ROUTE"
        bannerText = "DRAG FROM THE GATE TO THE CORE"
        bannerTimer = 3.4f
        audio.play("ui_click", 0.5f, 1.04f)
    }

    private fun returnToTitle() {
        if ((phase == GamePhase.BUILD || (phase == GamePhase.PAUSED && phaseBeforePause == GamePhase.BUILD)) && pathComplete) saveRun()
        phase = GamePhase.TITLE
        enemies.clear()
        pendingSpawns.clear()
        projectiles.clear()
        selectedTower = null
        selectedTrap = null
        bannerTimer = 0f
        savedRunAvailable = preferences.getBoolean("has_saved_run", false)
        audio.play("ui_click", 0.4f, 0.9f)
    }

    private fun pauseGame() {
        if (phase == GamePhase.PAUSED || phase == GamePhase.TITLE || phase == GamePhase.VICTORY || phase == GamePhase.GAME_OVER) return
        if (phase == GamePhase.BUILD && pathComplete) saveRun()
        phaseBeforePause = phase
        phase = GamePhase.PAUSED
        audio.play("ui_click", 0.3f, 0.88f)
    }

    private fun resumeGame() {
        if (phase == GamePhase.PAUSED) {
            phase = phaseBeforePause
            audio.play("ui_click", 0.35f, 1.08f)
        }
    }

    private fun resetPath() {
        if (waveNumber != 0 || (phase != GamePhase.DIG && phase != GamePhase.BUILD)) return
        clearSavedRun()
        pathCells.clear()
        pathCells.add(GridCell(0, START_ROW))
        towers.clear()
        traps.clear()
        gold = STARTING_BLOCKS
        pathComplete = false
        diggingGesture = false
        phase = GamePhase.DIG
        selectedTool = BuildTool.DIG
        selectedTower = null
        selectedTrap = null
        setBanner("PATH RESET  DRAG TO THE CORE", 2.2f)
        audio.play("dig", 0.4f, 0.78f)
    }

    private fun setBanner(message: String, duration: Float) {
        bannerText = message
        bannerTimer = duration
    }

    private fun burst(x: Float, y: Float, color: Int, count: Int, force: Float) {
        repeat(count) {
            val angle = random.nextFloat() * 6.28318f
            val speed = (0.25f + random.nextFloat() * 0.75f) * force
            val life = 0.28f + random.nextFloat() * 0.48f
            particles.add(Particle(x, y, cos(angle) * speed, sin(angle) * speed, life, life, color, 0.05f + random.nextFloat() * 0.08f, random.nextBoolean()))
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        synchronized(stateLock) {
            val x = event.x
            val y = event.y

            if (phase == GamePhase.TITLE) {
                if (event.action == MotionEvent.ACTION_UP) {
                    when {
                        titlePlayRect.contains(x, y) -> newRun()
                        titleContinueRect.contains(x, y) && savedRunAvailable -> loadSavedRun()
                        titleSoundRect.contains(x, y) -> audio.toggle()
                    }
                }
                return true
            }

            if (phase == GamePhase.VICTORY || phase == GamePhase.GAME_OVER) {
                if (event.action == MotionEvent.ACTION_UP) {
                    if (endPrimaryRect.contains(x, y)) newRun() else if (endSecondaryRect.contains(x, y)) returnToTitle()
                }
                return true
            }

            if (phase == GamePhase.PAUSED) {
                if (event.action == MotionEvent.ACTION_UP) {
                    if (endPrimaryRect.contains(x, y)) resumeGame() else if (endSecondaryRect.contains(x, y)) returnToTitle()
                }
                return true
            }

            if (event.action == MotionEvent.ACTION_CANCEL) {
                diggingGesture = false
                return true
            }
            if (event.action == MotionEvent.ACTION_UP && diggingGesture) {
                diggingGesture = false
                return true
            }

            if (event.action == MotionEvent.ACTION_UP) {
                if (pauseRect.contains(x, y)) {
                    pauseGame()
                    return true
                }
                if (soundRect.contains(x, y)) {
                    audio.toggle()
                    return true
                }
                if (waveNumber == 0 && resetPathRect.contains(x, y)) {
                    resetPath()
                    return true
                }
                if (primaryActionRect.contains(x, y) && phase == GamePhase.BUILD) {
                    startWave()
                    return true
                }
            }

            if ((selectedTower != null || selectedTrap != null) && phase == GamePhase.BUILD && event.action == MotionEvent.ACTION_UP) {
                if (backRect.contains(x, y)) {
                    selectedTower = null
                    selectedTrap = null
                    audio.play("ui_click", 0.3f, 1f)
                    return true
                }
                if (upgradeRect.contains(x, y)) {
                    upgradeSelectedDefense()
                    return true
                }
                if (sellRect.contains(x, y)) {
                    recycleSelectedDefense()
                    return true
                }
            }

            if (phase == GamePhase.BUILD && event.action == MotionEvent.ACTION_UP) {
                if (towerPageRect.contains(x, y)) {
                    buildPage = BuildPage.TOWERS
                    if (selectedTool.ordinal >= BuildTool.SPIKES.ordinal) selectedTool = BuildTool.BOLT
                    rebuildToolRects()
                    audio.play("ui_click", 0.28f, 1.02f)
                    return true
                }
                if (trapPageRect.contains(x, y)) {
                    buildPage = BuildPage.TRAPS
                    if (selectedTool.ordinal < BuildTool.SPIKES.ordinal) selectedTool = BuildTool.SPIKES
                    rebuildToolRects()
                    audio.play("ui_click", 0.28f, 0.96f)
                    return true
                }
                for (entry in toolRects) {
                    if (entry.second.contains(x, y)) {
                        selectTool(entry.first)
                        return true
                    }
                }
            }

            val cell = screenToCell(x, y)
            if (cell != null) {
                if (phase == GamePhase.DIG && event.action == MotionEvent.ACTION_DOWN) {
                    diggingGesture = true
                    extendPath(cell)
                    return true
                }
                if (diggingGesture && event.action == MotionEvent.ACTION_MOVE) {
                    extendPath(cell)
                    return true
                }
                if (event.action == MotionEvent.ACTION_UP) {
                    handleGridTap(cell)
                    return true
                }
            }
            return true
        }
    }

    private fun selectTool(tool: BuildTool) {
        if (phase != GamePhase.BUILD) return
        selectedTower = null
        selectedTrap = null
        selectedTool = tool
        audio.play("ui_click", 0.28f, 1f + tool.ordinal * 0.025f)
    }

    private fun extendPath(cell: GridCell) {
        if (pathComplete || pathCells.isEmpty()) return
        val last = pathCells[pathCells.size - 1]
        val distanceToTouch = abs(cell.col - last.col) + abs(cell.row - last.row)
        if (distanceToTouch > 1) {
            var safety = 0
            while (!pathComplete && pathCells[pathCells.size - 1] != cell && safety < COLS + ROWS) {
                val current = pathCells[pathCells.size - 1]
                val deltaCol = cell.col - current.col
                val deltaRow = cell.row - current.row
                val next = if (abs(deltaCol) >= abs(deltaRow) && deltaCol != 0) GridCell(current.col + if (deltaCol > 0) 1 else -1, current.row) else GridCell(current.col, current.row + if (deltaRow > 0) 1 else -1)
                val sizeBefore = pathCells.size
                extendPath(next)
                if (pathCells.size == sizeBefore) break
                safety += 1
            }
            return
        }
        if (pathCells.size >= 2 && cell == pathCells[pathCells.size - 2]) {
            pathCells.removeAt(pathCells.size - 1)
            audio.play("dig", 0.18f, 0.76f)
            return
        }
        if (abs(cell.col - last.col) + abs(cell.row - last.row) != 1 || pathCells.contains(cell)) return
        if (pathCells.size >= MAX_PATH_LENGTH) {
            setBanner("PATH LIMIT REACHED  CONNECT TO THE CORE", 1.7f)
            audio.play("ui_click", 0.25f, 0.65f)
            return
        }
        val isCore = cell.col == COLS - 1 && cell.row == START_ROW
        if (cell.col == COLS - 1 && !isCore) {
            setBanner("ENTER THE CORE THROUGH THE GLOWING BLOCK", 1.5f)
            return
        }
        pathCells.add(cell)
        burst(cell.col + 0.5f, cell.row + 0.5f, Color.rgb(153, 125, 82), 5, 0.42f)
        audio.play("dig", 0.16f, 0.92f + random.nextFloat() * 0.15f)
        if (isCore) {
            pathComplete = true
            phase = GamePhase.BUILD
            selectedTool = BuildTool.BOLT
            buildPage = BuildPage.TOWERS
            rebuildToolRects()
            setBanner("PATH LOCKED  BUILD YOUR DEFENSE", 2.6f)
            saveRun()
            audio.play("build", 0.55f, 1f)
        }
    }

    private fun handleGridTap(cell: GridCell) {
        val existingTower = findTower(cell.col, cell.row)
        if (existingTower != null) {
            selectedTower = existingTower
            selectedTrap = null
            audio.play("ui_click", 0.28f, 1.12f)
            return
        }
        val existingTrap = findTrap(cell.col, cell.row)
        if (existingTrap != null) {
            selectedTrap = existingTrap
            selectedTower = null
            audio.play("ui_click", 0.28f, 1.06f)
            return
        }
        if (phase != GamePhase.BUILD) return
        selectedTower = null
        selectedTrap = null
        when (selectedTool) {
            BuildTool.BOLT -> placeTower(cell, TowerKind.BOLT)
            BuildTool.FROST -> placeTower(cell, TowerKind.FROST)
            BuildTool.CANNON -> placeTower(cell, TowerKind.CANNON)
            BuildTool.EMBER -> placeTower(cell, TowerKind.EMBER)
            BuildTool.BEACON -> placeTower(cell, TowerKind.BEACON)
            BuildTool.SPIKES -> placeTrap(cell, TrapKind.SPIKE)
            BuildTool.ROOT -> placeTrap(cell, TrapKind.ROOT)
            BuildTool.RUNE -> placeTrap(cell, TrapKind.EMBER)
            BuildTool.ARC -> placeTrap(cell, TrapKind.ARC)
            BuildTool.CRUSHER -> placeTrap(cell, TrapKind.CRUSHER)
            BuildTool.DIG -> Unit
        }
    }

    private fun placeTower(cell: GridCell, kind: TowerKind) {
        if (isPathCell(cell) || findTower(cell.col, cell.row) != null || findTrap(cell.col, cell.row) != null) {
            setBanner("TOWERS NEED A FREE TERRAIN BLOCK", 1.5f)
            audio.play("ui_click", 0.24f, 0.7f)
            return
        }
        if (gold < kind.cost) {
            setBanner("NOT ENOUGH BLOCKS", 1.5f)
            audio.play("ui_click", 0.24f, 0.68f)
            return
        }
        gold -= kind.cost
        towers.add(Tower(cell.col, cell.row, kind))
        score = safeAdd(score, 20)
        burst(cell.col + 0.5f, cell.row + 0.5f, kind.accent, 12, 0.8f)
        audio.play("build", 0.42f, 0.92f + kind.ordinal * 0.07f)
        setBanner("${kind.title.toUpperCase()} ONLINE", 1.2f)
        saveRun()
    }

    private fun placeTrap(cell: GridCell, kind: TrapKind) {
        if (!isPathCell(cell) || cell == pathCells.first() || cell == pathCells.last() || findTrap(cell.col, cell.row) != null) {
            setBanner("TRAPS GO ON AN EMPTY PATH BLOCK", 1.5f)
            audio.play("ui_click", 0.24f, 0.7f)
            return
        }
        if (gold < kind.cost) {
            setBanner("NOT ENOUGH BLOCKS", 1.5f)
            audio.play("ui_click", 0.24f, 0.68f)
            return
        }
        gold -= kind.cost
        traps.add(SpikeTrap(nextTrapId++, cell.col, cell.row, kind))
        burst(cell.col + 0.5f, cell.row + 0.5f, kind.accent, 9, 0.65f)
        audio.play("build", 0.34f, 1.12f + kind.ordinal * 0.05f)
        setBanner("${kind.title.toUpperCase()} ARMED", 1.1f)
        saveRun()
    }

    private fun upgradeSelectedDefense() {
        val tower = selectedTower
        val trap = selectedTrap
        val cost = tower?.upgradeCost() ?: trap?.upgradeCost() ?: return
        if (gold < cost) {
            setBanner("NEED $cost BLOCKS TO UPGRADE", 1.5f)
            audio.play("ui_click", 0.24f, 0.68f)
            return
        }
        gold -= cost
        val x: Float
        val y: Float
        val accent: Int
        val title: String
        val rank: String
        if (tower != null) {
            if (tower.level < 3) tower.level += 1 else tower.overcharge += 1
            x = tower.col + 0.5f
            y = tower.row + 0.5f
            accent = tower.kind.accent
            title = tower.kind.title
            rank = tower.rankLabel()
        } else {
            trap ?: return
            if (trap.level < 3) trap.level += 1 else trap.overcharge += 1
            x = trap.col + 0.5f
            y = trap.row + 0.5f
            accent = trap.kind.accent
            title = trap.kind.title
            rank = trap.rankLabel()
        }
        score = safeAdd(score, 50)
        burst(x, y, accent, 18, 1.1f)
        audio.play("build", 0.55f, 1.16f)
        setBanner("${title.toUpperCase()}  $rank", 1.5f)
        saveRun()
    }

    private fun recycleSelectedDefense() {
        val tower = selectedTower
        val trap = selectedTrap
        val value = tower?.sellValue() ?: trap?.sellValue() ?: return
        gold = safeAdd(gold, value)
        if (tower != null) towers.remove(tower) else if (trap != null) traps.remove(trap)
        selectedTower = null
        selectedTrap = null
        audio.play("dig", 0.38f, 0.72f)
        setBanner("DEFENSE RECYCLED  +$value", 1.4f)
        saveRun()
    }

    private fun screenToCell(x: Float, y: Float): GridCell? {
        if (x < boardLeft || y < boardTop || x >= boardLeft + COLS * tileSize || y >= boardTop + ROWS * tileSize) return null
        val col = ((x - boardLeft) / tileSize).toInt()
        val row = ((y - boardTop) / tileSize).toInt()
        if (col !in 0 until COLS || row !in 0 until ROWS) return null
        return GridCell(col, row)
    }

    private fun isPathCell(cell: GridCell): Boolean = pathCells.contains(cell)

    private fun findTower(col: Int, row: Int): Tower? = towers.firstOrNull { it.col == col && it.row == row }

    private fun findTrap(col: Int, row: Int): SpikeTrap? = traps.firstOrNull { it.col == col && it.row == row }

    private fun drawFrame(canvas: Canvas) {
        if (phase == GamePhase.TITLE) {
            drawTitle(canvas)
            return
        }
        val shakeX = if (screenShake > 0f) (random.nextFloat() - 0.5f) * dp(5f) * min(1f, screenShake * 5f) else 0f
        val shakeY = if (screenShake > 0f) (random.nextFloat() - 0.5f) * dp(4f) * min(1f, screenShake * 5f) else 0f
        canvas.drawColor(Color.rgb(11, 17, 14))
        canvas.save()
        canvas.translate(shakeX, shakeY)
        drawBoard(canvas)
        drawTopBar(canvas)
        drawBottomBar(canvas)
        drawBanner(canvas)
        canvas.restore()
        if (phase == GamePhase.PAUSED) drawPauseOverlay(canvas) else if (phase == GamePhase.VICTORY || phase == GamePhase.GAME_OVER) drawEndOverlay(canvas)
    }

    private fun drawTitle(canvas: Canvas) {
        canvas.drawColor(Color.rgb(10, 17, 13))
        drawTitleGrid(canvas)
        drawRoundedRect(canvas, dp(18f), dp(16f), dp(62f), dp(60f), dp(12f), Color.rgb(190, 244, 78))
        drawCenteredText(canvas, "B", dp(40f), dp(39f), dp(24f), Color.rgb(13, 22, 17), true, true)
        drawText(canvas, "TECHTROY GAME LAB", dp(74f), dp(45f), dp(12f), Color.rgb(190, 244, 78), Paint.Align.LEFT, true)
        drawRoundedRect(canvas, titleSoundRect.left, titleSoundRect.top, titleSoundRect.right, titleSoundRect.bottom, dp(11f), Color.rgb(26, 39, 31))
        drawCenteredText(canvas, if (audio.isEnabled()) "SFX" else "OFF", titleSoundRect.centerX(), titleSoundRect.centerY(), dp(11f), Color.WHITE, true)

        val titleY = viewHeight * 0.27f
        drawCenteredText(canvas, "BLOCKHOLD DEFENSE", viewWidth * 0.5f, titleY, min(dp(51f), viewHeight * 0.105f), Color.WHITE, true, true)
        drawCenteredText(canvas, "ENDLESS PATHFORGE", viewWidth * 0.5f, titleY + min(dp(45f), viewHeight * 0.09f), min(dp(18f), viewHeight * 0.038f), Color.rgb(190, 244, 78), true)
        drawCenteredText(canvas, "DIG THE ROUTE   BUILD THE LINE   OUTLAST THE OVERGROWTH", viewWidth * 0.5f, titleY + min(dp(82f), viewHeight * 0.16f), min(dp(11f), viewHeight * 0.025f), Color.rgb(166, 180, 169), true)

        val featureY = viewHeight * 0.54f
        val featureWidth = min(dp(160f), viewWidth * 0.20f)
        val featureGap = dp(8f)
        val total = featureWidth * 3f + featureGap * 2f
        val startX = (viewWidth - total) * 0.5f
        drawFeaturePill(canvas, startX, featureY, featureWidth, "01", "FORGE A MAZE")
        drawFeaturePill(canvas, startX + featureWidth + featureGap, featureY, featureWidth, "02", "10 DEFENSES")
        drawFeaturePill(canvas, startX + (featureWidth + featureGap) * 2f, featureY, featureWidth, "∞", "SURVIVE")

        drawRoundedRect(canvas, titlePlayRect.left, titlePlayRect.top, titlePlayRect.right, titlePlayRect.bottom, dp(16f), Color.rgb(190, 244, 78))
        drawCenteredText(canvas, "START NEW RUN", titlePlayRect.centerX(), titlePlayRect.centerY(), dp(15f), Color.rgb(12, 21, 16), true, true)
        val continueBackground = if (savedRunAvailable) Color.rgb(93, 220, 255) else Color.rgb(27, 40, 32)
        val continueText = if (savedRunAvailable) Color.rgb(11, 25, 28) else Color.rgb(91, 108, 96)
        drawRoundedRect(canvas, titleContinueRect.left, titleContinueRect.top, titleContinueRect.right, titleContinueRect.bottom, dp(16f), continueBackground)
        drawCenteredText(canvas, if (savedRunAvailable) "CONTINUE RUN" else "NO SAVED RUN", titleContinueRect.centerX(), titleContinueRect.centerY(), dp(15f), continueText, true, true)
        drawCenteredText(canvas, "BEST WAVE  $bestWave    •    BEST SCORE  ${formatNumber(bestScore)}    •    v1.0", viewWidth * 0.5f, viewHeight - dp(18f), dp(10f), Color.rgb(113, 130, 119), true)
    }

    private fun drawTitleGrid(canvas: Canvas) {
        val block = max(dp(54f), viewHeight * 0.115f)
        val offset = (ambientTime * dp(5f)) % block
        var row = -1
        var y = -block + offset
        while (y < viewHeight + block) {
            var col = -1
            var x = -block
            while (x < viewWidth + block) {
                paint.style = Paint.Style.FILL
                paint.color = if ((col + row) % 2 == 0) Color.rgb(14, 25, 19) else Color.rgb(12, 22, 17)
                canvas.drawRect(x + 1f, y + 1f, x + block - 1f, y + block - 1f, paint)
                if (abs((col * 31 + row * 17) % 7) == 2) {
                    paint.color = Color.rgb(21, 38, 28)
                    canvas.drawRect(x + block * 0.22f, y + block * 0.24f, x + block * 0.31f, y + block * 0.33f, paint)
                }
                x += block
                col += 1
            }
            y += block
            row += 1
        }
        paint.color = Color.argb(135, 8, 14, 11)
        canvas.drawRect(0f, 0f, viewWidth, viewHeight, paint)
    }

    private fun drawFeaturePill(canvas: Canvas, x: Float, y: Float, width: Float, number: String, label: String) {
        val height = min(dp(54f), viewHeight * 0.105f)
        drawRoundedRect(canvas, x, y, x + width, y + height, dp(12f), Color.rgb(22, 34, 27))
        drawRoundedRect(canvas, x + dp(8f), y + dp(8f), x + dp(42f), y + height - dp(8f), dp(9f), Color.rgb(39, 56, 44))
        drawCenteredText(canvas, number, x + dp(25f), y + height * 0.5f, dp(12f), Color.rgb(190, 244, 78), true)
        drawText(canvas, label, x + dp(50f), y + height * 0.56f, min(dp(10f), width * 0.070f), Color.rgb(218, 226, 220), Paint.Align.LEFT, true)
    }

    private fun drawBoard(canvas: Canvas) {
        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(5, 9, 7)
        canvas.drawRoundRect(boardLeft - dp(6f), boardTop - dp(6f), boardLeft + COLS * tileSize + dp(6f), boardTop + ROWS * tileSize + dp(6f), dp(12f), dp(12f), paint)
        for (row in 0 until ROWS) for (col in 0 until COLS) drawTerrainTile(canvas, col, row)

        if (pathCells.size > 1) {
            strokePaint.style = Paint.Style.STROKE
            strokePaint.strokeWidth = max(2f, tileSize * 0.055f)
            strokePaint.strokeCap = Paint.Cap.ROUND
            strokePaint.color = Color.argb(125, 255, 241, 195)
            val route = Path()
            route.moveTo(cellCenterX(pathCells[0].col), cellCenterY(pathCells[0].row))
            for (i in 1 until pathCells.size) route.lineTo(cellCenterX(pathCells[i].col), cellCenterY(pathCells[i].row))
            canvas.drawPath(route, strokePaint)
        }

        drawGate(canvas)
        drawCore(canvas)
        for (trap in traps) drawTrap(canvas, trap, trap === selectedTrap)

        val chosenTower = selectedTower
        if (chosenTower != null) {
            paint.color = Color.argb(36, Color.red(chosenTower.kind.accent), Color.green(chosenTower.kind.accent), Color.blue(chosenTower.kind.accent))
            canvas.drawCircle(cellCenterX(chosenTower.col), cellCenterY(chosenTower.row), chosenTower.currentRange() * tileSize, paint)
            strokePaint.style = Paint.Style.STROKE
            strokePaint.strokeWidth = max(1.5f, tileSize * 0.025f)
            strokePaint.color = Color.argb(160, Color.red(chosenTower.kind.accent), Color.green(chosenTower.kind.accent), Color.blue(chosenTower.kind.accent))
            canvas.drawCircle(cellCenterX(chosenTower.col), cellCenterY(chosenTower.row), chosenTower.currentRange() * tileSize, strokePaint)
        }

        for (tower in towers) drawTower(canvas, tower, tower === chosenTower)
        for (enemy in enemies) drawEnemy(canvas, enemy)
        for (projectile in projectiles) drawProjectile(canvas, projectile)
        for (particle in particles) drawParticle(canvas, particle)
        for (label in floatingLabels) {
            val alpha = (min(1f, label.life * 2f) * 255f).toInt()
            val labelColor = Color.argb(alpha, Color.red(label.color), Color.green(label.color), Color.blue(label.color))
            drawCenteredText(canvas, label.message, gridX(label.x), gridY(label.y), max(dp(9f), tileSize * 0.20f), labelColor, true)
        }
    }

    private fun drawTerrainTile(canvas: Canvas, col: Int, row: Int) {
        val left = boardLeft + col * tileSize
        val top = boardTop + row * tileSize
        val destination = RectF(left, top, left + tileSize, top + tileSize)
        val pathTile = isPathCell(GridCell(col, row))
        spritePaint.alpha = 255
        canvas.drawBitmap(if (pathTile) sprites.path else sprites.grass, null, destination, spritePaint)
        if ((col * 41 + row * 67) % 5 == 0 && !pathTile) {
            paint.color = Color.argb(45, 20, 83, 35)
            canvas.drawCircle(left + tileSize * 0.23f, top + tileSize * 0.70f, tileSize * 0.07f, paint)
        }
        strokePaint.style = Paint.Style.STROKE
        strokePaint.strokeWidth = max(1f, tileSize * 0.015f)
        strokePaint.color = Color.argb(44, 8, 15, 11)
        canvas.drawRect(destination, strokePaint)
    }

    private fun drawGate(canvas: Canvas) {
        val x = cellCenterX(0)
        val y = cellCenterY(START_ROW)
        val pulse = 0.88f + sin(ambientTime * 3f) * 0.07f
        drawBitmapCentered(canvas, sprites.gatePart, x, y, tileSize * 0.90f, 90f)
        strokePaint.strokeWidth = tileSize * 0.045f
        strokePaint.color = Color.argb(190, 190, 244, 78)
        canvas.drawCircle(x, y, tileSize * 0.25f * pulse, strokePaint)
        drawCenteredText(canvas, "IN", x, y, max(dp(7f), tileSize * 0.14f), Color.rgb(12, 28, 18), true)
    }

    private fun drawCore(canvas: Canvas) {
        val x = cellCenterX(COLS - 1)
        val y = cellCenterY(START_ROW)
        val pulse = 0.5f + 0.5f * sin(ambientTime * 3.5f)
        paint.color = Color.argb((45 + pulse * 55).toInt(), 190, 244, 78)
        canvas.drawCircle(x, y, tileSize * (0.47f + pulse * 0.05f), paint)
        drawBitmapCentered(canvas, sprites.corePart, x, y, tileSize * 0.96f, -90f)
        paint.color = Color.rgb(224, 255, 169)
        canvas.drawCircle(x, y, tileSize * (0.09f + pulse * 0.015f), paint)
    }

    private fun drawTrap(canvas: Canvas, trap: SpikeTrap, selected: Boolean) {
        val x = cellCenterX(trap.col)
        val y = cellCenterY(trap.row)
        val scale = tileSize * (0.74f + trap.pulse * 0.10f)
        val bitmap = when (trap.kind) {
            TrapKind.SPIKE -> sprites.spikeTrap
            TrapKind.ROOT -> sprites.rootTrap
            TrapKind.EMBER -> sprites.emberTrap
            TrapKind.ARC -> sprites.arcTrap
            TrapKind.CRUSHER -> sprites.crusherTrap
        }
        if (selected) {
            strokePaint.strokeWidth = tileSize * 0.05f
            strokePaint.color = Color.WHITE
            canvas.drawCircle(x, y, tileSize * 0.39f, strokePaint)
        }
        drawBitmapCentered(canvas, bitmap, x, y, scale, if (trap.kind == TrapKind.SPIKE) 90f else 0f)
        if (trap.kind == TrapKind.ARC) {
            strokePaint.strokeWidth = tileSize * 0.035f
            strokePaint.color = trap.kind.accent
            canvas.drawCircle(x, y, tileSize * (0.18f + trap.pulse * 0.12f), strokePaint)
        }
        drawRankDots(canvas, x, y + tileSize * 0.34f, trap.level, trap.overcharge, trap.kind.accent)
    }

    private fun drawTower(canvas: Canvas, tower: Tower, selected: Boolean) {
        val x = cellCenterX(tower.col)
        val y = cellCenterY(tower.row)
        paint.color = Color.argb(80, 0, 0, 0)
        canvas.drawOval(x - tileSize * 0.30f, y + tileSize * 0.23f, x + tileSize * 0.30f, y + tileSize * 0.39f, paint)
        if (selected) {
            strokePaint.strokeWidth = tileSize * 0.055f
            strokePaint.color = Color.WHITE
            canvas.drawCircle(x, y, tileSize * 0.42f, strokePaint)
        }
        val base = when (tower.kind) {
            TowerKind.BOLT -> sprites.towerBase
            TowerKind.FROST -> sprites.frostBase
            TowerKind.CANNON -> sprites.cannonBase
            TowerKind.EMBER -> sprites.emberBase
            TowerKind.BEACON -> sprites.beaconBase
        }
        drawBitmapCentered(canvas, base, x, y + tileSize * 0.05f, tileSize * 0.88f)
        when (tower.kind) {
            TowerKind.BOLT -> drawBitmapCentered(canvas, sprites.greenTurret, x, y - tower.recoil * tileSize * 0.035f, tileSize * 0.86f, tower.angle * 57.29578f)
            TowerKind.FROST -> {
                drawBitmapCentered(canvas, sprites.paleTurret, x, y, tileSize * 0.82f, tower.angle * 57.29578f)
                paint.color = Color.argb(80, 93, 220, 255)
                canvas.drawCircle(x, y, tileSize * 0.21f, paint)
            }
            TowerKind.CANNON -> drawBitmapCentered(canvas, sprites.cannonTurret, x, y - tileSize * 0.04f, tileSize * 0.82f, tower.angle * 57.29578f + 90f)
            TowerKind.EMBER -> drawBitmapCentered(canvas, sprites.emberFlame, x, y - tileSize * 0.10f, tileSize * (0.70f + sin(ambientTime * 6f) * 0.03f))
            TowerKind.BEACON -> drawBitmapCentered(canvas, sprites.beaconPulse, x, y - tileSize * 0.03f, tileSize * (0.70f + sin(ambientTime * 4f) * 0.05f), ambientTime * 30f)
        }
        if (tower.disabledTimer > 0f) {
            paint.color = Color.argb(145, 130, 48, 165)
            canvas.drawCircle(x, y, tileSize * 0.34f, paint)
            drawCenteredText(canvas, "HEX", x, y, tileSize * 0.13f, Color.WHITE, true)
        }
        drawRankDots(canvas, x, y + tileSize * 0.37f, tower.level, tower.overcharge, tower.kind.accent)
    }

    private fun drawRankDots(canvas: Canvas, x: Float, y: Float, level: Int, overcharge: Int, accent: Int) {
        paint.color = accent
        for (rank in 0 until level) canvas.drawCircle(x + (rank - 1) * tileSize * 0.10f, y, tileSize * 0.031f, paint)
        if (overcharge > 0) {
            paint.color = Color.WHITE
            drawCenteredText(canvas, "+$overcharge", x + tileSize * 0.31f, y, max(dp(6f), tileSize * 0.11f), Color.WHITE, true)
        }
    }

    private fun drawEnemy(canvas: Canvas, enemy: Enemy) {
        if (!enemy.alive) return
        val x = gridX(enemy.x)
        val bob = sin(enemy.animation) * tileSize * 0.025f
        val y = gridY(enemy.y) + bob
        val size = tileSize * enemy.kind.scale
        val healthRatio = max(0f, enemy.health / enemy.maxHealth)
        paint.color = Color.argb(90, 0, 0, 0)
        canvas.drawOval(x - size * 0.34f, y + size * 0.25f, x + size * 0.34f, y + size * 0.42f, paint)
        paint.color = Color.argb(if (enemy.kind.elite || enemy.kind.boss) 115 else 70, Color.red(enemy.kind.color), Color.green(enemy.kind.color), Color.blue(enemy.kind.color))
        canvas.drawCircle(x, y, size * 0.46f, paint)
        if (enemy.kind.elite || enemy.kind.boss) {
            strokePaint.strokeWidth = tileSize * (if (enemy.kind.boss) 0.055f else 0.035f)
            strokePaint.color = if (enemy.kind.boss) Color.rgb(255, 207, 90) else enemy.kind.color
            canvas.drawCircle(x, y, size * 0.50f, strokePaint)
        }
        spritePaint.alpha = if (enemy.flashTimer > 0f) 155 else 255
        drawBitmapCentered(canvas, sprites.enemy(enemy.kind), x, y, size * 1.02f)
        spritePaint.alpha = 255
        if (enemy.slowTimer > 0f || enemy.stunTimer > 0f) {
            strokePaint.strokeWidth = max(1.5f, tileSize * 0.025f)
            strokePaint.color = if (enemy.stunTimer > 0f) Color.rgb(195, 120, 255) else Color.rgb(93, 220, 255)
            canvas.drawCircle(x, y, size * 0.53f, strokePaint)
        }
        if (enemy.burnTimer > 0f) drawBitmapCentered(canvas, sprites.emberTrap, x + size * 0.25f, y - size * 0.26f, size * 0.35f)
        if (healthRatio < 0.995f || enemy.kind.elite || enemy.kind.boss) {
            val barWidth = size * 0.90f
            val barY = y - size * 0.62f
            val barHeight = max(3f, tileSize * 0.06f)
            paint.color = Color.argb(205, 13, 19, 16)
            canvas.drawRoundRect(x - barWidth * 0.5f, barY, x + barWidth * 0.5f, barY + barHeight, tileSize * 0.03f, tileSize * 0.03f, paint)
            paint.color = if (healthRatio > 0.35f) Color.rgb(190, 244, 78) else Color.rgb(255, 91, 84)
            canvas.drawRoundRect(x - barWidth * 0.5f, barY, x - barWidth * 0.5f + barWidth * healthRatio, barY + barHeight, tileSize * 0.03f, tileSize * 0.03f, paint)
        }
        if (enemy.kind.boss) drawCenteredText(canvas, "T${enemy.bossTier}", x, y + size * 0.58f, max(dp(7f), tileSize * 0.12f), Color.rgb(255, 222, 126), true)
    }

    private fun drawProjectile(canvas: Canvas, projectile: Projectile) {
        val x = gridX(projectile.x)
        val y = gridY(projectile.y)
        val radius = when (projectile.kind) {
            TowerKind.BOLT -> tileSize * 0.07f
            TowerKind.FROST -> tileSize * 0.09f
            TowerKind.CANNON -> tileSize * 0.14f
            TowerKind.EMBER -> tileSize * 0.11f
            TowerKind.BEACON -> tileSize * 0.085f
        }
        paint.color = Color.argb(55, Color.red(projectile.kind.accent), Color.green(projectile.kind.accent), Color.blue(projectile.kind.accent))
        canvas.drawCircle(x, y, radius * 2.2f, paint)
        paint.color = projectile.kind.accent
        canvas.drawCircle(x, y, radius, paint)
        if (projectile.kind == TowerKind.FROST || projectile.kind == TowerKind.BEACON) {
            paint.color = Color.WHITE
            canvas.drawCircle(x - radius * 0.25f, y - radius * 0.25f, radius * 0.28f, paint)
        }
    }

    private fun drawParticle(canvas: Canvas, particle: Particle) {
        val alphaRatio = max(0f, particle.life / particle.maxLife)
        paint.color = Color.argb((alphaRatio * 220f).toInt(), Color.red(particle.color), Color.green(particle.color), Color.blue(particle.color))
        val x = gridX(particle.x)
        val y = gridY(particle.y)
        val size = max(2f, particle.size * tileSize * alphaRatio)
        if (particle.square) canvas.drawRect(x - size, y - size, x + size, y + size, paint) else canvas.drawCircle(x, y, size, paint)
    }

    private fun drawTopBar(canvas: Canvas) {
        paint.color = Color.rgb(13, 22, 17)
        canvas.drawRect(0f, 0f, viewWidth, topBarHeight, paint)
        paint.color = Color.rgb(190, 244, 78)
        canvas.drawRect(0f, topBarHeight - max(2f, dp(2f)), viewWidth, topBarHeight, paint)

        drawRoundedRect(canvas, dp(10f), dp(8f), dp(48f), topBarHeight - dp(8f), dp(10f), Color.rgb(190, 244, 78))
        drawCenteredText(canvas, "B", dp(29f), topBarHeight * 0.5f, min(dp(21f), topBarHeight * 0.42f), Color.rgb(13, 22, 17), true, true)
        drawText(canvas, "BLOCKHOLD", dp(58f), topBarHeight * 0.43f, min(dp(14f), topBarHeight * 0.27f), Color.WHITE, Paint.Align.LEFT, true, true)
        drawText(canvas, phaseLabel(), dp(58f), topBarHeight * 0.72f, min(dp(8f), topBarHeight * 0.17f), Color.rgb(139, 157, 144), Paint.Align.LEFT, true)

        val statStart = min(viewWidth * 0.27f, dp(265f))
        val statGap = min(dp(102f), viewWidth * 0.115f)
        drawStat(canvas, statStart, "BLOCKS", formatNumber(gold), Color.rgb(190, 244, 78))
        drawStat(canvas, statStart + statGap, "CORE", "$lives/$STARTING_CORE", Color.rgb(255, 111, 100))
        drawStat(canvas, statStart + statGap * 2f, "WAVE", waveNumber.toString(), Color.rgb(93, 220, 255))

        if (waveNumber == 0 && (phase == GamePhase.DIG || phase == GamePhase.BUILD)) drawTopButton(canvas, resetPathRect, "RESET", Color.rgb(36, 50, 40), Color.rgb(214, 224, 216), true)
        val canStart = phase == GamePhase.BUILD && pathComplete
        val actionLabel = when (phase) {
            GamePhase.DIG -> "CONNECT CORE"
            GamePhase.BUILD -> "START WAVE ${waveNumber + 1}"
            GamePhase.WAVE -> "WAVE LIVE"
            else -> "STANDBY"
        }
        drawTopButton(canvas, primaryActionRect, actionLabel, if (canStart) Color.rgb(190, 244, 78) else Color.rgb(31, 44, 35), if (canStart) Color.rgb(13, 22, 17) else Color.rgb(104, 123, 110), canStart)
        drawTopButton(canvas, soundRect, if (audio.isEnabled()) "SFX" else "OFF", Color.rgb(31, 44, 35), Color.WHITE, true)
        drawTopButton(canvas, pauseRect, "II", Color.rgb(31, 44, 35), Color.WHITE, true)
    }

    private fun drawStat(canvas: Canvas, x: Float, label: String, value: String, accent: Int) {
        drawText(canvas, label, x, topBarHeight * 0.37f, min(dp(8f), topBarHeight * 0.16f), Color.rgb(115, 135, 121), Paint.Align.LEFT, true)
        drawText(canvas, value, x, topBarHeight * 0.70f, min(dp(15f), topBarHeight * 0.30f), accent, Paint.Align.LEFT, true, true)
    }

    private fun drawTopButton(canvas: Canvas, rect: RectF, label: String, background: Int, foreground: Int, active: Boolean) {
        drawRoundedRect(canvas, rect.left, rect.top, rect.right, rect.bottom, dp(10f), background)
        if (active) {
            strokePaint.strokeWidth = max(1f, dp(1f))
            strokePaint.color = Color.argb(55, 255, 255, 255)
            canvas.drawRoundRect(rect, dp(10f), dp(10f), strokePaint)
        }
        drawCenteredText(canvas, label, rect.centerX(), rect.centerY(), min(dp(9f), rect.height() * 0.27f), foreground, true)
    }

    private fun phaseLabel(): String {
        return when (phase) {
            GamePhase.DIG -> "PATH FORGE"
            GamePhase.BUILD -> "ENDLESS BUILD"
            GamePhase.WAVE -> waveTheme
            GamePhase.PAUSED -> "RUN PAUSED"
            else -> "ENDLESS PATHFORGE"
        }
    }

    private fun drawBottomBar(canvas: Canvas) {
        val top = viewHeight - bottomBarHeight
        paint.color = Color.rgb(13, 22, 17)
        canvas.drawRect(0f, top, viewWidth, viewHeight, paint)
        paint.color = Color.rgb(32, 47, 37)
        canvas.drawRect(0f, top, viewWidth, top + max(1f, dp(1f)), paint)
        when {
            phase == GamePhase.DIG -> drawPathForgePanel(canvas)
            (selectedTower != null || selectedTrap != null) && phase == GamePhase.BUILD -> drawDefensePanel(canvas)
            else -> drawToolBar(canvas)
        }
    }

    private fun drawPathForgePanel(canvas: Canvas) {
        val top = viewHeight - bottomBarHeight + dp(13f)
        val bottom = viewHeight - dp(13f)
        val width = min(viewWidth - dp(28f), dp(650f))
        val left = (viewWidth - width) * 0.5f
        drawRoundedRect(canvas, left, top, left + width, bottom, dp(14f), Color.rgb(24, 38, 29))
        drawCenteredText(canvas, "DRAW THE ONLY ROUTE", left + width * 0.31f, top + (bottom - top) * 0.38f, min(dp(15f), bottomBarHeight * 0.16f), Color.rgb(190, 244, 78), true, true)
        drawCenteredText(canvas, "DRAG BLOCK BY BLOCK • BACKTRACK TO UNDO", left + width * 0.31f, top + (bottom - top) * 0.69f, min(dp(9f), bottomBarHeight * 0.095f), Color.rgb(172, 188, 177), true)
        drawBitmapCentered(canvas, sprites.path, left + width * 0.72f, (top + bottom) * 0.5f, min(bottom - top, dp(76f)))
        drawCenteredText(canvas, "${pathCells.size}/$MAX_PATH_LENGTH", left + width * 0.89f, (top + bottom) * 0.5f, dp(13f), Color.WHITE, true)
    }

    private fun drawToolBar(canvas: Canvas) {
        drawPageTab(canvas, towerPageRect, "TOWERS", buildPage == BuildPage.TOWERS, Color.rgb(190, 244, 78))
        drawPageTab(canvas, trapPageRect, "TRAPS", buildPage == BuildPage.TRAPS, Color.rgb(93, 220, 255))
        for ((tool, rect) in toolRects) {
            val selected = selectedTool == tool
            val affordable = gold >= tool.cost
            val accent = toolAccent(tool)
            val background = if (selected) Color.argb(220, Color.red(accent), Color.green(accent), Color.blue(accent)) else Color.rgb(25, 38, 30)
            drawRoundedRect(canvas, rect.left, rect.top, rect.right, rect.bottom, dp(11f), background)
            if (selected) {
                strokePaint.strokeWidth = max(2f, dp(2f))
                strokePaint.color = Color.WHITE
                canvas.drawRoundRect(rect, dp(11f), dp(11f), strokePaint)
            }
            drawToolIcon(canvas, tool, rect.centerX(), rect.top + rect.height() * 0.34f, min(rect.width(), rect.height()) * 0.32f, if (selected) Color.rgb(13, 22, 17) else accent)
            drawCenteredText(canvas, tool.title, rect.centerX(), rect.top + rect.height() * 0.68f, min(dp(9f), rect.width() * 0.12f), if (selected) Color.rgb(13, 22, 17) else Color.WHITE, true)
            drawCenteredText(canvas, tool.cost.toString(), rect.centerX(), rect.top + rect.height() * 0.87f, min(dp(8f), rect.width() * 0.105f), if (selected) Color.rgb(22, 38, 27) else if (affordable) Color.rgb(190, 244, 78) else Color.rgb(255, 105, 94), true)
        }
    }

    private fun drawPageTab(canvas: Canvas, rect: RectF, label: String, selected: Boolean, accent: Int) {
        drawRoundedRect(canvas, rect.left, rect.top, rect.right, rect.bottom, dp(9f), if (selected) accent else Color.rgb(27, 42, 33))
        drawCenteredText(canvas, label, rect.centerX(), rect.centerY(), min(dp(9f), rect.width() * 0.12f), if (selected) Color.rgb(12, 22, 17) else Color.rgb(177, 192, 181), true)
    }

    private fun toolAccent(tool: BuildTool): Int {
        return when (tool) {
            BuildTool.BOLT -> TowerKind.BOLT.accent
            BuildTool.FROST -> TowerKind.FROST.accent
            BuildTool.CANNON -> TowerKind.CANNON.accent
            BuildTool.EMBER -> TowerKind.EMBER.accent
            BuildTool.BEACON -> TowerKind.BEACON.accent
            BuildTool.SPIKES -> TrapKind.SPIKE.accent
            BuildTool.ROOT -> TrapKind.ROOT.accent
            BuildTool.RUNE -> TrapKind.EMBER.accent
            BuildTool.ARC -> TrapKind.ARC.accent
            BuildTool.CRUSHER -> TrapKind.CRUSHER.accent
            BuildTool.DIG -> Color.rgb(153, 125, 82)
        }
    }

    private fun drawToolIcon(canvas: Canvas, tool: BuildTool, x: Float, y: Float, size: Float, color: Int) {
        val bitmap: Bitmap? = when (tool) {
            BuildTool.BOLT -> sprites.greenTurret
            BuildTool.FROST -> sprites.paleTurret
            BuildTool.CANNON -> sprites.cannonTurret
            BuildTool.EMBER -> sprites.emberFlame
            BuildTool.BEACON -> sprites.beaconPulse
            BuildTool.SPIKES -> sprites.spikeTrap
            BuildTool.ROOT -> sprites.rootTrap
            BuildTool.RUNE -> sprites.emberTrap
            BuildTool.ARC -> sprites.arcTrap
            BuildTool.CRUSHER -> sprites.crusherTrap
            BuildTool.DIG -> null
        }
        if (bitmap != null) {
            drawBitmapCentered(canvas, bitmap, x, y, size * 2.1f, if (tool == BuildTool.SPIKES) 90f else 0f)
        } else {
            paint.color = color
            canvas.drawRect(x - size * 0.45f, y - size * 0.14f, x + size * 0.45f, y + size * 0.14f, paint)
        }
    }

    private fun drawDefensePanel(canvas: Canvas) {
        val tower = selectedTower
        val trap = selectedTrap
        val title = tower?.kind?.title ?: trap?.kind?.title ?: return
        val accent = tower?.kind?.accent ?: trap?.kind?.accent ?: Color.WHITE
        val cost = tower?.upgradeCost() ?: trap?.upgradeCost() ?: 0
        val canBuy = gold >= cost
        val damage = tower?.currentDamage() ?: trap?.currentDamage() ?: 0f
        val range = tower?.currentRange()
        val rank = tower?.rankLabel() ?: trap?.rankLabel().orEmpty()
        val sellValue = tower?.sellValue() ?: trap?.sellValue() ?: 0
        drawRoundedRect(canvas, backRect.left, backRect.top, backRect.right, backRect.bottom, dp(12f), Color.rgb(25, 38, 30))
        drawCenteredText(canvas, "BACK", backRect.centerX(), backRect.centerY(), dp(11f), Color.WHITE, true)
        val upgradeColor = if (canBuy) accent else Color.rgb(27, 42, 33)
        drawRoundedRect(canvas, upgradeRect.left, upgradeRect.top, upgradeRect.right, upgradeRect.bottom, dp(12f), upgradeColor)
        val titleColor = if (canBuy) Color.rgb(12, 21, 16) else Color.rgb(150, 168, 156)
        drawCenteredText(canvas, "UPGRADE ${title.toUpperCase()}  •  $rank", upgradeRect.centerX(), upgradeRect.top + upgradeRect.height() * 0.37f, min(dp(10f), upgradeRect.width() * 0.030f), titleColor, true)
        val stats = if (range != null) "DMG ${damage.toInt()}  RANGE ${oneDecimal(range)}  •  $cost BLOCKS" else "DMG ${damage.toInt()}  •  $cost BLOCKS"
        drawCenteredText(canvas, stats, upgradeRect.centerX(), upgradeRect.top + upgradeRect.height() * 0.70f, min(dp(9f), upgradeRect.width() * 0.026f), titleColor, true)
        drawRoundedRect(canvas, sellRect.left, sellRect.top, sellRect.right, sellRect.bottom, dp(12f), Color.rgb(50, 37, 32))
        drawCenteredText(canvas, "RECYCLE", sellRect.centerX(), sellRect.top + sellRect.height() * 0.40f, dp(10f), Color.rgb(255, 188, 126), true)
        drawCenteredText(canvas, "+$sellValue", sellRect.centerX(), sellRect.top + sellRect.height() * 0.70f, dp(9f), Color.rgb(197, 154, 112), true)
    }

    private fun drawBanner(canvas: Canvas) {
        val instruction = when {
            bannerTimer > 0f -> bannerText
            phase == GamePhase.DIG -> "DRAG ONE BLOCK AT A TIME  •  BACKTRACK TO UNDO  •  MAX $MAX_PATH_LENGTH"
            phase == GamePhase.BUILD && waveNumber == 0 && towers.isEmpty() -> "CHOOSE A DEFENSE BELOW  •  TAP A FREE BLOCK TO BUILD"
            phase == GamePhase.BUILD -> "SPEND BLOCKS  •  OVERCHARGE PAST LEVEL 3  •  START WHEN READY"
            phase == GamePhase.WAVE -> "$waveTheme  •  TOWERS ARE AUTONOMOUS"
            else -> ""
        }
        if (instruction.isEmpty()) return
        val alpha = if (bannerTimer in 0f..0.35f) (bannerTimer / 0.35f * 220f).toInt() else 220
        val width = min(viewWidth * 0.69f, dp(610f))
        val height = min(dp(28f), tileSize * 0.48f)
        val left = (viewWidth - width) * 0.5f
        val top = boardTop + dp(7f)
        drawRoundedRect(canvas, left, top, left + width, top + height, height * 0.45f, Color.argb(alpha, 14, 23, 18))
        drawCenteredText(canvas, instruction, viewWidth * 0.5f, top + height * 0.52f, min(dp(9f), height * 0.34f), Color.argb(min(255, alpha + 25), 224, 232, 226), true)
    }

    private fun drawPauseOverlay(canvas: Canvas) {
        paint.color = Color.argb(218, 7, 13, 10)
        canvas.drawRect(0f, 0f, viewWidth, viewHeight, paint)
        drawCenteredText(canvas, "RUN PAUSED", viewWidth * 0.5f, viewHeight * 0.34f, min(dp(43f), viewHeight * 0.10f), Color.WHITE, true, true)
        val note = if (phaseBeforePause == GamePhase.WAVE) "RESUME THIS WAVE  •  MENU RETURNS TO LAST CHECKPOINT" else "PATH, DEFENSES, SCORE, AND WAVE ARE SAVED"
        drawCenteredText(canvas, note, viewWidth * 0.5f, viewHeight * 0.44f, dp(11f), Color.rgb(153, 171, 159), true)
        drawEndButtons(canvas, "RESUME", "MAIN MENU")
    }

    private fun drawEndOverlay(canvas: Canvas) {
        paint.color = Color.argb(226, 7, 13, 10)
        canvas.drawRect(0f, 0f, viewWidth, viewHeight, paint)
        val victory = phase == GamePhase.VICTORY
        val accent = if (victory) Color.rgb(190, 244, 78) else Color.rgb(255, 102, 92)
        drawCenteredText(canvas, if (victory) "CORE SECURED" else "CORE BREACHED", viewWidth * 0.5f, viewHeight * 0.28f, min(dp(48f), viewHeight * 0.105f), accent, true, true)
        drawCenteredText(canvas, if (victory) "ENDLESS MODE COMPLETE" else "THE RUN ENDS  THE HIGH WAVE REMAINS", viewWidth * 0.5f, viewHeight * 0.39f, dp(12f), Color.rgb(184, 198, 188), true)
        val cardWidth = min(dp(400f), viewWidth * 0.60f)
        val cardLeft = (viewWidth - cardWidth) * 0.5f
        val cardTop = viewHeight * 0.47f
        val cardBottom = viewHeight * 0.63f
        drawRoundedRect(canvas, cardLeft, cardTop, cardLeft + cardWidth, cardBottom, dp(14f), Color.rgb(20, 32, 25))
        drawResultStat(canvas, cardLeft + cardWidth * 0.18f, cardTop, cardBottom, "SCORE", formatNumber(score), accent)
        drawResultStat(canvas, cardLeft + cardWidth * 0.50f, cardTop, cardBottom, "WAVE", waveNumber.toString(), Color.rgb(93, 220, 255))
        drawResultStat(canvas, cardLeft + cardWidth * 0.82f, cardTop, cardBottom, "BEST WAVE", bestWave.toString(), Color.rgb(255, 188, 96))
        drawEndButtons(canvas, "NEW RUN", "MAIN MENU")
    }

    private fun drawResultStat(canvas: Canvas, x: Float, top: Float, bottom: Float, label: String, value: String, color: Int) {
        drawCenteredText(canvas, label, x, top + (bottom - top) * 0.36f, dp(9f), Color.rgb(118, 137, 124), true)
        drawCenteredText(canvas, value, x, top + (bottom - top) * 0.68f, dp(19f), color, true, true)
    }

    private fun drawEndButtons(canvas: Canvas, primary: String, secondary: String) {
        drawRoundedRect(canvas, endPrimaryRect.left, endPrimaryRect.top, endPrimaryRect.right, endPrimaryRect.bottom, dp(14f), Color.rgb(190, 244, 78))
        drawCenteredText(canvas, primary, endPrimaryRect.centerX(), endPrimaryRect.centerY(), dp(13f), Color.rgb(12, 21, 16), true)
        drawRoundedRect(canvas, endSecondaryRect.left, endSecondaryRect.top, endSecondaryRect.right, endSecondaryRect.bottom, dp(14f), Color.rgb(29, 43, 34))
        drawCenteredText(canvas, secondary, endSecondaryRect.centerX(), endSecondaryRect.centerY(), dp(13f), Color.WHITE, true)
    }

    private fun drawBitmapCentered(canvas: Canvas, bitmap: Bitmap, x: Float, y: Float, size: Float, rotation: Float = 0f) {
        val scale = size / max(bitmap.width, bitmap.height).toFloat()
        val halfWidth = bitmap.width * scale * 0.5f
        val halfHeight = bitmap.height * scale * 0.5f
        val destination = RectF(x - halfWidth, y - halfHeight, x + halfWidth, y + halfHeight)
        if (rotation != 0f) {
            canvas.save()
            canvas.rotate(rotation, x, y)
            canvas.drawBitmap(bitmap, null, destination, spritePaint)
            canvas.restore()
        } else {
            canvas.drawBitmap(bitmap, null, destination, spritePaint)
        }
    }

    private fun drawRoundedRect(canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float, radius: Float, color: Int) {
        paint.style = Paint.Style.FILL
        paint.color = color
        canvas.drawRoundRect(left, top, right, bottom, radius, radius, paint)
    }

    private fun drawText(
        canvas: Canvas,
        value: String,
        x: Float,
        baselineY: Float,
        size: Float,
        color: Int,
        align: Paint.Align,
        bold: Boolean,
        black: Boolean = false
    ) {
        paint.style = Paint.Style.FILL
        paint.color = color
        paint.textSize = size
        paint.textAlign = align
        paint.typeface = if (black) blackTypeface else if (bold) boldTypeface else regularTypeface
        canvas.drawText(value, x, baselineY, paint)
    }

    private fun drawCenteredText(
        canvas: Canvas,
        value: String,
        centerX: Float,
        centerY: Float,
        size: Float,
        color: Int,
        bold: Boolean,
        black: Boolean = false
    ) {
        paint.textSize = size
        paint.typeface = if (black) blackTypeface else if (bold) boldTypeface else regularTypeface
        val metrics = paint.fontMetrics
        val baseline = centerY - (metrics.ascent + metrics.descent) * 0.5f
        drawText(canvas, value, centerX, baseline, size, color, Paint.Align.CENTER, bold, black)
    }

    private fun cellCenterX(col: Int): Float {
        return boardLeft + (col + 0.5f) * tileSize
    }

    private fun cellCenterY(row: Int): Float {
        return boardTop + (row + 0.5f) * tileSize
    }

    private fun gridX(value: Float): Float {
        return boardLeft + value * tileSize
    }

    private fun gridY(value: Float): Float {
        return boardTop + value * tileSize
    }

    private fun dp(value: Float): Float {
        return value * density
    }

    private fun darker(color: Int, multiplier: Float): Int {
        return Color.rgb(
            min(255, (Color.red(color) * multiplier).toInt()),
            min(255, (Color.green(color) * multiplier).toInt()),
            min(255, (Color.blue(color) * multiplier).toInt())
        )
    }

    private fun lighter(color: Int, multiplier: Float): Int {
        return darker(color, multiplier)
    }

    private fun oneDecimal(value: Float): String {
        return (value * 10f).toInt().toString().let {
            if (it.length == 1) "0.$it" else it.substring(0, it.length - 1) + "." + it.substring(it.length - 1)
        }
    }

    private fun formatNumber(value: Int): String {
        return when {
            value >= 1_000_000 -> "${oneDecimal(value / 1_000_000f)}M"
            value >= 10_000 -> "${oneDecimal(value / 1_000f)}K"
            else -> value.toString()
        }
    }
}
