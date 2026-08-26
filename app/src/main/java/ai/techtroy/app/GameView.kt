package ai.techtroy.app

import android.content.Context
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
import kotlin.math.sin
import kotlin.math.sqrt

internal class GameView(context: Context) : SurfaceView(context), SurfaceHolder.Callback, Runnable {

    companion object {
        private const val COLS = 12
        private const val ROWS = 7
        private const val START_ROW = 3
        private const val MAX_WAVES = 5
        private const val MAX_PATH_LENGTH = 46
    }

    private val stateLock = Any()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val boldTypeface = Typeface.create("sans-serif", Typeface.BOLD)
    private val blackTypeface = Typeface.create("sans-serif-black", Typeface.NORMAL)
    private val regularTypeface = Typeface.create("sans-serif", Typeface.NORMAL)
    private val random = Random(7331L)
    private val audio = AudioEngine(context)
    private val preferences = context.getSharedPreferences("blockhold_progress", Context.MODE_PRIVATE)

    @Volatile private var running = false
    @Volatile private var activityPaused = false
    private var renderThread: Thread? = null

    private var phase = GamePhase.TITLE
    private var phaseBeforePause = GamePhase.BUILD
    private var selectedTool = BuildTool.DIG
    private var selectedTower: Tower? = null

    private val pathCells = ArrayList<GridCell>()
    private val towers = ArrayList<Tower>()
    private val traps = ArrayList<SpikeTrap>()
    private val enemies = ArrayList<Enemy>()
    private val projectiles = ArrayList<Projectile>()
    private val particles = ArrayList<Particle>()
    private val floatingLabels = ArrayList<FloatingLabel>()
    private val waveQueue = ArrayList<EnemyKind>()

    private var gold = 320
    private var lives = 10
    private var score = 0
    private var bestScore = preferences.getInt("best_score", 0)
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
    private val titleSoundRect = RectF()
    private val endPrimaryRect = RectF()
    private val endSecondaryRect = RectF()
    private val upgradeRect = RectF()
    private val sellRect = RectF()
    private val backRect = RectF()
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
        if (running) {
            return
        }
        running = true
        renderThread = Thread(this, "BlockholdGameLoop")
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
            if (delta > 0.04f) {
                delta = 0.04f
            }

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
                    if (canvas != null) {
                        holder.unlockCanvasAndPost(canvas)
                    }
                }
            }

            val elapsedMs = (System.nanoTime() - frameStart) / 1_000_000L
            val sleepMs = 16L - elapsedMs
            if (sleepMs > 1L) {
                SystemClock.sleep(sleepMs)
            }
        }
    }

    fun pauseFromActivity() {
        synchronized(stateLock) {
            if (phase == GamePhase.WAVE || phase == GamePhase.BUILD || phase == GamePhase.DIG) {
                phaseBeforePause = phase
                phase = GamePhase.PAUSED
            }
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
                GamePhase.PAUSED -> returnToTitle()
                GamePhase.VICTORY, GamePhase.GAME_OVER -> returnToTitle()
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
        bottomBarHeight = min(viewHeight * 0.225f, dp(106f))
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

        toolRects.clear()
        val tools = BuildTool.values()
        val gap = dp(7f)
        val totalWidth = min(viewWidth - dp(24f), dp(680f))
        val buttonWidth = (totalWidth - gap * (tools.size - 1)) / tools.size
        var x = (viewWidth - totalWidth) * 0.5f
        val toolTop = viewHeight - bottomBarHeight + dp(9f)
        val toolBottom = viewHeight - dp(9f)
        for (tool in tools) {
            val rect = RectF(x, toolTop, x + buttonWidth, toolBottom)
            toolRects.add(Pair(tool, rect))
            x += buttonWidth + gap
        }

        val panelWidth = min(viewWidth - dp(24f), dp(610f))
        val panelLeft = (viewWidth - panelWidth) * 0.5f
        val panelTop = viewHeight - bottomBarHeight + dp(12f)
        val panelBottom = viewHeight - dp(12f)
        val panelGap = dp(8f)
        backRect.set(panelLeft, panelTop, panelLeft + panelWidth * 0.18f, panelBottom)
        upgradeRect.set(backRect.right + panelGap, panelTop, backRect.right + panelGap + panelWidth * 0.48f, panelBottom)
        sellRect.set(upgradeRect.right + panelGap, panelTop, panelLeft + panelWidth, panelBottom)

        val titleButtonWidth = min(dp(260f), viewWidth * 0.42f)
        val titleButtonHeight = min(dp(64f), viewHeight * 0.14f)
        titlePlayRect.set(
            (viewWidth - titleButtonWidth) * 0.5f,
            viewHeight * 0.69f,
            (viewWidth + titleButtonWidth) * 0.5f,
            viewHeight * 0.69f + titleButtonHeight
        )
        titleSoundRect.set(viewWidth - dp(58f), dp(14f), viewWidth - dp(14f), dp(58f))

        val endButtonWidth = min(dp(230f), viewWidth * 0.34f)
        val endButtonHeight = min(dp(58f), viewHeight * 0.13f)
        endPrimaryRect.set(
            viewWidth * 0.5f - endButtonWidth - dp(6f),
            viewHeight * 0.68f,
            viewWidth * 0.5f - dp(6f),
            viewHeight * 0.68f + endButtonHeight
        )
        endSecondaryRect.set(
            viewWidth * 0.5f + dp(6f),
            viewHeight * 0.68f,
            viewWidth * 0.5f + endButtonWidth + dp(6f),
            viewHeight * 0.68f + endButtonHeight
        )
    }

    private fun update(delta: Float) {
        ambientTime += delta
        if (bannerTimer > 0f) {
            bannerTimer -= delta
        }
        if (screenShake > 0f) {
            screenShake -= delta
        }

        if (phase == GamePhase.WAVE) {
            updateWave(delta)
        }
        updateEffects(delta)
    }

    private fun updateWave(delta: Float) {
        if (spawnIndex < waveQueue.size) {
            spawnTimer -= delta
            if (spawnTimer <= 0f) {
                spawnEnemy(waveQueue[spawnIndex])
                spawnIndex += 1
                spawnTimer = if (waveNumber >= 4) 0.72f else 0.82f
            }
        }

        for (enemy in enemies) {
            if (!enemy.alive) {
                continue
            }
            enemy.animation += delta * (4f + enemy.kind.speed * 2f)
            enemy.flashTimer = max(0f, enemy.flashTimer - delta)
            enemy.slowTimer = max(0f, enemy.slowTimer - delta)
            val slowMultiplier = if (enemy.slowTimer > 0f) 0.56f else 1f
            enemy.progress += enemy.kind.speed * slowMultiplier * delta
            updateEnemyPosition(enemy)
            triggerTrapIfNeeded(enemy)

            if (enemy.progress >= pathCells.size - 1f) {
                enemy.alive = false
                lives -= enemy.kind.baseDamage
                if (lives < 0) {
                    lives = 0
                }
                audio.play("base_hit", 0.75f, if (enemy.kind == EnemyKind.OVERGROWTH) 0.72f else 1f)
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
        removeDefeatedEnemies()

        if (spawnIndex >= waveQueue.size && enemies.isEmpty() && projectiles.isEmpty() && phase == GamePhase.WAVE) {
            completeWave()
        }
    }

    private fun spawnEnemy(kind: EnemyKind) {
        if (pathCells.size < 2) {
            return
        }
        val enemy = Enemy(nextEnemyId++, kind)
        updateEnemyPosition(enemy)
        enemies.add(enemy)
    }

    private fun updateEnemyPosition(enemy: Enemy) {
        val lastIndex = pathCells.size - 1
        if (lastIndex <= 0) {
            return
        }
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
            if (trap.col == cell.col && trap.row == cell.row && !enemy.triggeredTraps.contains(trap.id)) {
                enemy.triggeredTraps.add(trap.id)
                trap.pulse = 1f
                damageEnemy(enemy, 34f, Color.rgb(233, 238, 216))
                burst(enemy.x, enemy.y, Color.rgb(222, 229, 205), 8, 0.8f)
                audio.play("dig", 0.32f, 1.35f)
            }
        }
    }

    private fun findTarget(tower: Tower): Enemy? {
        var best: Enemy? = null
        var bestProgress = -1f
        val centerX = tower.col + 0.5f
        val centerY = tower.row + 0.5f
        val rangeSquared = tower.currentRange() * tower.currentRange()
        for (enemy in enemies) {
            if (!enemy.alive) {
                continue
            }
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
        projectiles.add(
            Projectile(
                tower.col + 0.5f,
                tower.row + 0.5f,
                target,
                tower.kind,
                tower.currentDamage(),
                tower.kind.projectileSpeed
            )
        )
        when (tower.kind) {
            TowerKind.BOLT -> audio.play("bolt", 0.22f, 0.94f + random.nextFloat() * 0.12f)
            TowerKind.FROST -> audio.play("frost", 0.20f, 0.96f + random.nextFloat() * 0.10f)
            TowerKind.CANNON -> {
                audio.play("cannon", 0.42f, 0.92f + random.nextFloat() * 0.08f)
                screenShake = max(screenShake, 0.09f)
            }
        }
    }

    private fun updateProjectiles(delta: Float) {
        for (projectile in projectiles) {
            if (!projectile.alive) {
                continue
            }
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
        val iterator = projectiles.iterator()
        while (iterator.hasNext()) {
            if (!iterator.next().alive) {
                iterator.remove()
            }
        }
    }

    private fun impactProjectile(projectile: Projectile) {
        when (projectile.kind) {
            TowerKind.BOLT -> {
                damageEnemy(projectile.target, projectile.damage, projectile.kind.accent)
                burst(projectile.x, projectile.y, projectile.kind.accent, 5, 0.55f)
            }
            TowerKind.FROST -> {
                damageEnemy(projectile.target, projectile.damage, projectile.kind.accent)
                projectile.target.slowTimer = max(projectile.target.slowTimer, 1.9f)
                burst(projectile.x, projectile.y, projectile.kind.accent, 8, 0.7f)
            }
            TowerKind.CANNON -> {
                for (enemy in enemies) {
                    if (!enemy.alive) {
                        continue
                    }
                    val dx = enemy.x - projectile.x
                    val dy = enemy.y - projectile.y
                    val distance = sqrt(dx * dx + dy * dy)
                    if (distance <= 0.9f) {
                        val multiplier = 1f - distance * 0.35f
                        damageEnemy(enemy, projectile.damage * multiplier, projectile.kind.accent)
                    }
                }
                burst(projectile.x, projectile.y, projectile.kind.accent, 18, 1.5f)
                screenShake = max(screenShake, 0.18f)
            }
        }
    }

    private fun damageEnemy(enemy: Enemy, amount: Float, effectColor: Int) {
        if (!enemy.alive) {
            return
        }
        enemy.health -= amount
        enemy.flashTimer = 0.11f
        if (random.nextFloat() < 0.22f) {
            floatingLabels.add(FloatingLabel(amount.toInt().toString(), enemy.x, enemy.y - 0.25f, effectColor, 0.7f))
        }
        if (enemy.health <= 0f) {
            enemy.alive = false
            if (!enemy.rewarded) {
                enemy.rewarded = true
                gold += enemy.kind.bounty
                score += enemy.kind.bounty * 10
                floatingLabels.add(FloatingLabel("+${enemy.kind.bounty}", enemy.x, enemy.y, Color.rgb(190, 244, 78)))
                burst(enemy.x, enemy.y, enemy.kind.color, if (enemy.kind == EnemyKind.OVERGROWTH) 30 else 12, 1.2f)
                audio.play("enemy_down", if (enemy.kind == EnemyKind.OVERGROWTH) 0.65f else 0.22f, if (enemy.kind == EnemyKind.OVERGROWTH) 0.65f else 1.05f)
            }
        }
    }

    private fun removeDefeatedEnemies() {
        val iterator = enemies.iterator()
        while (iterator.hasNext()) {
            if (!iterator.next().alive) {
                iterator.remove()
            }
        }
    }

    private fun updateEffects(delta: Float) {
        for (trap in traps) {
            trap.pulse = max(0f, trap.pulse - delta * 3.2f)
        }
        for (particle in particles) {
            particle.life -= delta
            particle.x += particle.velocityX * delta
            particle.y += particle.velocityY * delta
            particle.velocityY += 0.45f * delta
            particle.velocityX *= 0.985f
        }
        val particleIterator = particles.iterator()
        while (particleIterator.hasNext()) {
            if (particleIterator.next().life <= 0f) {
                particleIterator.remove()
            }
        }

        for (label in floatingLabels) {
            label.life -= delta
            label.y -= delta * 0.38f
        }
        val labelIterator = floatingLabels.iterator()
        while (labelIterator.hasNext()) {
            if (labelIterator.next().life <= 0f) {
                labelIterator.remove()
            }
        }
    }

    private fun completeWave() {
        if (waveNumber >= MAX_WAVES) {
            finishRun(true)
            return
        }
        phase = GamePhase.BUILD
        val reward = 50 + waveNumber * 12
        gold += reward
        score += reward * 5
        selectedTower = null
        setBanner("WAVE CLEARED  +$reward BLOCKS", 2.4f)
        audio.play("build", 0.45f, 1.14f)
    }

    private fun finishRun(victory: Boolean) {
        phase = if (victory) GamePhase.VICTORY else GamePhase.GAME_OVER
        selectedTower = null
        if (score > bestScore) {
            bestScore = score
            preferences.edit().putInt("best_score", bestScore).apply()
        }
        if (victory) {
            audio.play("victory", 0.8f, 1f)
            burst(COLS - 0.5f, START_ROW + 0.5f, Color.rgb(190, 244, 78), 50, 2.2f)
        }
    }

    private fun startWave() {
        if (phase != GamePhase.BUILD || !pathComplete) {
            return
        }
        waveNumber += 1
        waveQueue.clear()
        buildWaveQueue(waveNumber)
        spawnIndex = 0
        spawnTimer = 0.25f
        selectedTower = null
        phase = GamePhase.WAVE
        setBanner(if (waveNumber == MAX_WAVES) "FINAL WAVE  BOSS INBOUND" else "WAVE $waveNumber INBOUND", 2.1f)
        audio.play("wave", 0.65f, if (waveNumber == MAX_WAVES) 0.82f else 1f)
    }

    private fun buildWaveQueue(wave: Int) {
        when (wave) {
            1 -> repeat(7) { waveQueue.add(EnemyKind.MOSSER) }
            2 -> {
                repeat(5) { waveQueue.add(EnemyKind.MOSSER) }
                repeat(4) { waveQueue.add(EnemyKind.RUNNER) }
            }
            3 -> {
                repeat(4) {
                    waveQueue.add(EnemyKind.MOSSER)
                    waveQueue.add(EnemyKind.RUNNER)
                }
                waveQueue.add(EnemyKind.BRUTE)
            }
            4 -> {
                repeat(3) {
                    waveQueue.add(EnemyKind.RUNNER)
                    waveQueue.add(EnemyKind.BRUTE)
                }
                repeat(3) { waveQueue.add(EnemyKind.MOSSER) }
            }
            else -> {
                repeat(3) {
                    waveQueue.add(EnemyKind.BRUTE)
                    waveQueue.add(EnemyKind.RUNNER)
                }
                repeat(2) { waveQueue.add(EnemyKind.MOSSER) }
                waveQueue.add(EnemyKind.OVERGROWTH)
            }
        }
    }

    private fun newRun() {
        phase = GamePhase.DIG
        selectedTool = BuildTool.DIG
        selectedTower = null
        pathCells.clear()
        pathCells.add(GridCell(0, START_ROW))
        towers.clear()
        traps.clear()
        enemies.clear()
        projectiles.clear()
        particles.clear()
        floatingLabels.clear()
        waveQueue.clear()
        gold = 320
        lives = 10
        score = 0
        waveNumber = 0
        nextEnemyId = 1
        nextTrapId = 1
        pathComplete = false
        diggingGesture = false
        bannerText = "DRAG FROM THE GATE TO THE CORE"
        bannerTimer = 3.4f
        audio.play("ui_click", 0.5f, 1.04f)
    }

    private fun returnToTitle() {
        phase = GamePhase.TITLE
        enemies.clear()
        projectiles.clear()
        selectedTower = null
        bannerTimer = 0f
        audio.play("ui_click", 0.4f, 0.9f)
    }

    private fun pauseGame() {
        if (phase == GamePhase.PAUSED || phase == GamePhase.TITLE || phase == GamePhase.VICTORY || phase == GamePhase.GAME_OVER) {
            return
        }
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
        if (waveNumber != 0 || (phase != GamePhase.DIG && phase != GamePhase.BUILD)) {
            return
        }
        pathCells.clear()
        pathCells.add(GridCell(0, START_ROW))
        towers.clear()
        traps.clear()
        gold = 320
        pathComplete = false
        diggingGesture = false
        phase = GamePhase.DIG
        selectedTool = BuildTool.DIG
        selectedTower = null
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
            particles.add(
                Particle(
                    x,
                    y,
                    cos(angle) * speed,
                    sin(angle) * speed,
                    life,
                    life,
                    color,
                    0.05f + random.nextFloat() * 0.08f,
                    random.nextBoolean()
                )
            )
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        synchronized(stateLock) {
            val x = event.x
            val y = event.y

            if (phase == GamePhase.TITLE) {
                if (event.action == MotionEvent.ACTION_UP) {
                    if (titlePlayRect.contains(x, y)) {
                        newRun()
                    } else if (titleSoundRect.contains(x, y)) {
                        audio.toggle()
                    }
                }
                return true
            }

            if (phase == GamePhase.VICTORY || phase == GamePhase.GAME_OVER) {
                if (event.action == MotionEvent.ACTION_UP) {
                    if (endPrimaryRect.contains(x, y)) {
                        newRun()
                    } else if (endSecondaryRect.contains(x, y)) {
                        returnToTitle()
                    }
                }
                return true
            }

            if (phase == GamePhase.PAUSED) {
                if (event.action == MotionEvent.ACTION_UP) {
                    if (endPrimaryRect.contains(x, y)) {
                        resumeGame()
                    } else if (endSecondaryRect.contains(x, y)) {
                        returnToTitle()
                    }
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

            if (selectedTower != null && phase == GamePhase.BUILD && event.action == MotionEvent.ACTION_UP) {
                if (backRect.contains(x, y)) {
                    selectedTower = null
                    audio.play("ui_click", 0.3f, 1f)
                    return true
                }
                if (upgradeRect.contains(x, y)) {
                    upgradeSelectedTower()
                    return true
                }
                if (sellRect.contains(x, y)) {
                    sellSelectedTower()
                    return true
                }
            }

            if ((phase == GamePhase.DIG || phase == GamePhase.BUILD) && event.action == MotionEvent.ACTION_UP) {
                for (entry in toolRects) {
                    if (entry.second.contains(x, y)) {
                        selectTool(entry.first)
                        return true
                    }
                }
            }

            val cell = screenToCell(x, y)
            if (cell != null) {
                if (phase == GamePhase.DIG && selectedTool == BuildTool.DIG && event.action == MotionEvent.ACTION_DOWN) {
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
        if (phase != GamePhase.DIG && phase != GamePhase.BUILD) {
            return
        }
        if (tool == BuildTool.DIG && pathComplete) {
            setBanner("THE PATH IS LOCKED FOR THIS RUN", 1.6f)
            audio.play("ui_click", 0.25f, 0.72f)
            return
        }
        selectedTower = null
        selectedTool = tool
        audio.play("ui_click", 0.28f, 1f + tool.ordinal * 0.035f)
    }

    private fun extendPath(cell: GridCell) {
        if (pathComplete || pathCells.isEmpty()) {
            return
        }
        val last = pathCells[pathCells.size - 1]
        val distanceToTouch = abs(cell.col - last.col) + abs(cell.row - last.row)
        if (distanceToTouch > 1) {
            var safety = 0
            while (!pathComplete && pathCells[pathCells.size - 1] != cell && safety < COLS + ROWS) {
                val current = pathCells[pathCells.size - 1]
                val deltaCol = cell.col - current.col
                val deltaRow = cell.row - current.row
                val next = if (abs(deltaCol) >= abs(deltaRow) && deltaCol != 0) {
                    GridCell(current.col + if (deltaCol > 0) 1 else -1, current.row)
                } else {
                    GridCell(current.col, current.row + if (deltaRow > 0) 1 else -1)
                }
                val sizeBefore = pathCells.size
                extendPath(next)
                if (pathCells.size == sizeBefore) {
                    break
                }
                safety += 1
            }
            return
        }
        if (pathCells.size >= 2) {
            val previous = pathCells[pathCells.size - 2]
            if (cell == previous) {
                pathCells.removeAt(pathCells.size - 1)
                audio.play("dig", 0.18f, 0.76f)
                return
            }
        }
        if (abs(cell.col - last.col) + abs(cell.row - last.row) != 1) {
            return
        }
        if (pathCells.contains(cell)) {
            return
        }
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
            setBanner("PATH LOCKED  BUILD YOUR DEFENSE", 2.6f)
            audio.play("build", 0.55f, 1f)
        }
    }

    private fun handleGridTap(cell: GridCell) {
        val existingTower = findTower(cell.col, cell.row)
        if (existingTower != null) {
            selectedTower = existingTower
            audio.play("ui_click", 0.28f, 1.12f)
            return
        }
        if (phase != GamePhase.BUILD) {
            return
        }
        selectedTower = null
        when (selectedTool) {
            BuildTool.BOLT -> placeTower(cell, TowerKind.BOLT)
            BuildTool.FROST -> placeTower(cell, TowerKind.FROST)
            BuildTool.CANNON -> placeTower(cell, TowerKind.CANNON)
            BuildTool.SPIKES -> placeTrap(cell)
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
        val tower = Tower(cell.col, cell.row, kind)
        towers.add(tower)
        selectedTower = null
        score += 20
        burst(cell.col + 0.5f, cell.row + 0.5f, kind.accent, 12, 0.8f)
        audio.play("build", 0.42f, 0.92f + kind.ordinal * 0.08f)
        setBanner("${kind.title.toUpperCase()} ONLINE", 1.2f)
    }

    private fun placeTrap(cell: GridCell) {
        if (!isPathCell(cell) || cell == pathCells.first() || cell == pathCells.last() || findTrap(cell.col, cell.row) != null) {
            setBanner("SPIKES GO ON AN EMPTY PATH BLOCK", 1.5f)
            audio.play("ui_click", 0.24f, 0.7f)
            return
        }
        if (gold < BuildTool.SPIKES.cost) {
            setBanner("NOT ENOUGH BLOCKS", 1.5f)
            return
        }
        gold -= BuildTool.SPIKES.cost
        traps.add(SpikeTrap(nextTrapId++, cell.col, cell.row))
        burst(cell.col + 0.5f, cell.row + 0.5f, Color.rgb(222, 229, 205), 8, 0.6f)
        audio.play("build", 0.34f, 1.22f)
        setBanner("SPIKE BED ARMED", 1.1f)
    }

    private fun upgradeSelectedTower() {
        val tower = selectedTower ?: return
        if (tower.level >= 3) {
            setBanner("MAXIMUM TOWER LEVEL", 1.4f)
            audio.play("ui_click", 0.25f, 0.74f)
            return
        }
        val cost = tower.upgradeCost()
        if (gold < cost) {
            setBanner("NEED $cost BLOCKS TO UPGRADE", 1.5f)
            audio.play("ui_click", 0.24f, 0.68f)
            return
        }
        gold -= cost
        tower.level += 1
        score += 50
        burst(tower.col + 0.5f, tower.row + 0.5f, tower.kind.accent, 18, 1.1f)
        audio.play("build", 0.55f, 1.16f)
        setBanner("${tower.kind.title.toUpperCase()}  LEVEL ${tower.level}", 1.5f)
    }

    private fun sellSelectedTower() {
        val tower = selectedTower ?: return
        val value = tower.sellValue()
        gold += value
        towers.remove(tower)
        selectedTower = null
        audio.play("dig", 0.38f, 0.72f)
        setBanner("TOWER RECYCLED  +$value", 1.4f)
    }

    private fun screenToCell(x: Float, y: Float): GridCell? {
        if (x < boardLeft || y < boardTop || x >= boardLeft + COLS * tileSize || y >= boardTop + ROWS * tileSize) {
            return null
        }
        val col = ((x - boardLeft) / tileSize).toInt()
        val row = ((y - boardTop) / tileSize).toInt()
        if (col !in 0 until COLS || row !in 0 until ROWS) {
            return null
        }
        return GridCell(col, row)
    }

    private fun isPathCell(cell: GridCell): Boolean {
        return pathCells.contains(cell)
    }

    private fun findTower(col: Int, row: Int): Tower? {
        for (tower in towers) {
            if (tower.col == col && tower.row == row) {
                return tower
            }
        }
        return null
    }

    private fun findTrap(col: Int, row: Int): SpikeTrap? {
        for (trap in traps) {
            if (trap.col == col && trap.row == row) {
                return trap
            }
        }
        return null
    }

    private fun drawFrame(canvas: Canvas) {
        if (phase == GamePhase.TITLE) {
            drawTitle(canvas)
            return
        }

        val shakeX: Float
        val shakeY: Float
        if (screenShake > 0f) {
            shakeX = (random.nextFloat() - 0.5f) * dp(5f) * min(1f, screenShake * 5f)
            shakeY = (random.nextFloat() - 0.5f) * dp(4f) * min(1f, screenShake * 5f)
        } else {
            shakeX = 0f
            shakeY = 0f
        }

        canvas.drawColor(Color.rgb(11, 17, 14))
        canvas.save()
        canvas.translate(shakeX, shakeY)
        drawBoard(canvas)
        drawTopBar(canvas)
        drawBottomBar(canvas)
        drawBanner(canvas)
        canvas.restore()

        if (phase == GamePhase.PAUSED) {
            drawPauseOverlay(canvas)
        } else if (phase == GamePhase.VICTORY || phase == GamePhase.GAME_OVER) {
            drawEndOverlay(canvas)
        }
    }

    private fun drawTitle(canvas: Canvas) {
        canvas.drawColor(Color.rgb(10, 17, 13))
        drawTitleGrid(canvas)

        drawRoundedRect(canvas, dp(18f), dp(16f), dp(62f), dp(60f), dp(12f), Color.rgb(190, 244, 78))
        drawCenteredText(canvas, "B", dp(40f), dp(39f), dp(24f), Color.rgb(13, 22, 17), true, true)
        drawText(canvas, "TECHTROY GAME LAB", dp(74f), dp(45f), dp(12f), Color.rgb(190, 244, 78), Paint.Align.LEFT, true)

        drawRoundedRect(
            canvas,
            titleSoundRect.left,
            titleSoundRect.top,
            titleSoundRect.right,
            titleSoundRect.bottom,
            dp(11f),
            Color.rgb(26, 39, 31)
        )
        drawCenteredText(canvas, if (audio.isEnabled()) "SFX" else "OFF", titleSoundRect.centerX(), titleSoundRect.centerY(), dp(11f), Color.WHITE, true)

        val titleY = viewHeight * 0.28f
        drawCenteredText(canvas, "BLOCKHOLD", viewWidth * 0.5f, titleY, min(dp(58f), viewHeight * 0.115f), Color.WHITE, true, true)
        drawCenteredText(canvas, "PATHFORGE DEFENSE", viewWidth * 0.5f, titleY + min(dp(48f), viewHeight * 0.10f), min(dp(18f), viewHeight * 0.038f), Color.rgb(190, 244, 78), true)
        drawCenteredText(
            canvas,
            "DIG THE PATH   BUILD THE LINE   HOLD THE CORE",
            viewWidth * 0.5f,
            titleY + min(dp(88f), viewHeight * 0.17f),
            min(dp(12f), viewHeight * 0.027f),
            Color.rgb(166, 180, 169),
            true
        )

        val featureY = viewHeight * 0.56f
        val featureWidth = min(dp(150f), viewWidth * 0.19f)
        val featureGap = dp(8f)
        val total = featureWidth * 3f + featureGap * 2f
        val startX = (viewWidth - total) * 0.5f
        drawFeaturePill(canvas, startX, featureY, featureWidth, "01", "FORGE A MAZE")
        drawFeaturePill(canvas, startX + featureWidth + featureGap, featureY, featureWidth, "02", "BUILD TOWERS")
        drawFeaturePill(canvas, startX + (featureWidth + featureGap) * 2f, featureY, featureWidth, "03", "BREAK 5 WAVES")

        drawRoundedRect(
            canvas,
            titlePlayRect.left,
            titlePlayRect.top,
            titlePlayRect.right,
            titlePlayRect.bottom,
            dp(16f),
            Color.rgb(190, 244, 78)
        )
        drawCenteredText(canvas, "START NEW RUN", titlePlayRect.centerX(), titlePlayRect.centerY(), dp(16f), Color.rgb(12, 21, 16), true, true)

        drawCenteredText(
            canvas,
            "BEST SCORE  $bestScore    •    FIRST PLAYABLE  v0.1",
            viewWidth * 0.5f,
            viewHeight - dp(18f),
            dp(10f),
            Color.rgb(113, 130, 119),
            true
        )
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
                val hash = abs((col * 31 + row * 17) % 7)
                val color = if ((col + row) % 2 == 0) Color.rgb(14, 25, 19) else Color.rgb(12, 22, 17)
                paint.style = Paint.Style.FILL
                paint.color = color
                canvas.drawRect(x + 1f, y + 1f, x + block - 1f, y + block - 1f, paint)
                if (hash == 2) {
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
        drawCenteredText(canvas, number, x + dp(25f), y + height * 0.5f, dp(10f), Color.rgb(190, 244, 78), true)
        drawText(canvas, label, x + dp(50f), y + height * 0.56f, min(dp(10f), width * 0.075f), Color.rgb(218, 226, 220), Paint.Align.LEFT, true)
    }

    private fun drawBoard(canvas: Canvas) {
        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(5, 9, 7)
        canvas.drawRoundRect(
            boardLeft - dp(6f),
            boardTop - dp(6f),
            boardLeft + COLS * tileSize + dp(6f),
            boardTop + ROWS * tileSize + dp(6f),
            dp(12f),
            dp(12f),
            paint
        )

        for (row in 0 until ROWS) {
            for (col in 0 until COLS) {
                drawTerrainTile(canvas, col, row)
            }
        }

        if (pathCells.size > 1) {
            strokePaint.style = Paint.Style.STROKE
            strokePaint.strokeWidth = max(2f, tileSize * 0.07f)
            strokePaint.strokeCap = Paint.Cap.ROUND
            strokePaint.color = Color.argb(150, 226, 219, 184)
            val route = Path()
            val first = pathCells[0]
            route.moveTo(cellCenterX(first.col), cellCenterY(first.row))
            for (i in 1 until pathCells.size) {
                route.lineTo(cellCenterX(pathCells[i].col), cellCenterY(pathCells[i].row))
            }
            canvas.drawPath(route, strokePaint)
        }

        drawGate(canvas)
        drawCore(canvas)

        for (trap in traps) {
            drawTrap(canvas, trap)
        }

        val chosenTower = selectedTower
        if (chosenTower != null) {
            paint.style = Paint.Style.FILL
            paint.color = Color.argb(36, Color.red(chosenTower.kind.accent), Color.green(chosenTower.kind.accent), Color.blue(chosenTower.kind.accent))
            canvas.drawCircle(
                cellCenterX(chosenTower.col),
                cellCenterY(chosenTower.row),
                chosenTower.currentRange() * tileSize,
                paint
            )
            strokePaint.style = Paint.Style.STROKE
            strokePaint.strokeWidth = max(1.5f, tileSize * 0.025f)
            strokePaint.color = Color.argb(160, Color.red(chosenTower.kind.accent), Color.green(chosenTower.kind.accent), Color.blue(chosenTower.kind.accent))
            canvas.drawCircle(
                cellCenterX(chosenTower.col),
                cellCenterY(chosenTower.row),
                chosenTower.currentRange() * tileSize,
                strokePaint
            )
        }

        for (tower in towers) {
            drawTower(canvas, tower, tower === chosenTower)
        }
        for (enemy in enemies) {
            drawEnemy(canvas, enemy)
        }
        for (projectile in projectiles) {
            drawProjectile(canvas, projectile)
        }
        for (particle in particles) {
            drawParticle(canvas, particle)
        }
        for (label in floatingLabels) {
            val alpha = (min(1f, label.life * 2f) * 255f).toInt()
            val labelColor = Color.argb(alpha, Color.red(label.color), Color.green(label.color), Color.blue(label.color))
            drawCenteredText(canvas, label.message, gridX(label.x), gridY(label.y), max(dp(9f), tileSize * 0.20f), labelColor, true)
        }
    }

    private fun drawTerrainTile(canvas: Canvas, col: Int, row: Int) {
        val left = boardLeft + col * tileSize
        val top = boardTop + row * tileSize
        val right = left + tileSize
        val bottom = top + tileSize
        val cell = GridCell(col, row)
        val isPath = isPathCell(cell)

        paint.style = Paint.Style.FILL
        if (isPath) {
            paint.color = if ((col + row) % 2 == 0) Color.rgb(103, 92, 72) else Color.rgb(94, 84, 66)
        } else {
            paint.color = if ((col + row) % 2 == 0) Color.rgb(69, 100, 58) else Color.rgb(64, 94, 54)
        }
        canvas.drawRect(left, top, right, bottom, paint)

        paint.color = if (isPath) Color.argb(85, 230, 215, 172) else Color.argb(75, 171, 207, 104)
        canvas.drawRect(left, top, right, top + max(2f, tileSize * 0.08f), paint)

        val hash = abs(col * 41 + row * 67)
        if (hash % 3 == 0) {
            paint.color = if (isPath) Color.argb(90, 60, 53, 42) else Color.argb(95, 42, 73, 39)
            val mark = tileSize * 0.10f
            val mx = left + tileSize * (0.20f + (hash % 5) * 0.11f)
            val my = top + tileSize * (0.35f + (hash % 4) * 0.10f)
            canvas.drawRect(mx, my, mx + mark, my + mark * 0.55f, paint)
        }

        strokePaint.style = Paint.Style.STROKE
        strokePaint.strokeWidth = max(1f, tileSize * 0.018f)
        strokePaint.color = Color.argb(70, 8, 15, 11)
        canvas.drawRect(left, top, right, bottom, strokePaint)
    }

    private fun drawGate(canvas: Canvas) {
        val x = cellCenterX(0)
        val y = cellCenterY(START_ROW)
        val pulse = 0.82f + sin(ambientTime * 3f) * 0.08f
        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(21, 34, 27)
        canvas.drawRect(x - tileSize * 0.42f, y - tileSize * 0.36f, x - tileSize * 0.18f, y + tileSize * 0.36f, paint)
        canvas.drawRect(x + tileSize * 0.18f, y - tileSize * 0.36f, x + tileSize * 0.42f, y + tileSize * 0.36f, paint)
        paint.color = Color.rgb(190, 244, 78)
        canvas.drawRect(x - tileSize * 0.37f, y - tileSize * 0.31f, x - tileSize * 0.25f, y + tileSize * 0.31f, paint)
        canvas.drawRect(x + tileSize * 0.25f, y - tileSize * 0.31f, x + tileSize * 0.37f, y + tileSize * 0.31f, paint)
        strokePaint.style = Paint.Style.STROKE
        strokePaint.strokeWidth = tileSize * 0.04f
        strokePaint.color = Color.argb(180, 190, 244, 78)
        canvas.drawCircle(x, y, tileSize * 0.23f * pulse, strokePaint)
    }

    private fun drawCore(canvas: Canvas) {
        val x = cellCenterX(COLS - 1)
        val y = cellCenterY(START_ROW)
        val pulse = 0.5f + 0.5f * sin(ambientTime * 3.5f)
        paint.style = Paint.Style.FILL
        paint.color = Color.argb((40 + pulse * 40).toInt(), 190, 244, 78)
        canvas.drawCircle(x, y, tileSize * (0.46f + pulse * 0.05f), paint)
        paint.color = Color.rgb(18, 30, 23)
        canvas.drawRoundRect(
            x - tileSize * 0.34f,
            y - tileSize * 0.34f,
            x + tileSize * 0.34f,
            y + tileSize * 0.34f,
            tileSize * 0.08f,
            tileSize * 0.08f,
            paint
        )
        paint.color = Color.rgb(190, 244, 78)
        val diamond = Path()
        diamond.moveTo(x, y - tileSize * 0.24f)
        diamond.lineTo(x + tileSize * 0.22f, y)
        diamond.lineTo(x, y + tileSize * 0.24f)
        diamond.lineTo(x - tileSize * 0.22f, y)
        diamond.close()
        canvas.drawPath(diamond, paint)
        paint.color = Color.rgb(233, 255, 187)
        canvas.drawCircle(x - tileSize * 0.05f, y - tileSize * 0.07f, tileSize * 0.045f, paint)
    }

    private fun drawTrap(canvas: Canvas, trap: SpikeTrap) {
        val x = cellCenterX(trap.col)
        val y = cellCenterY(trap.row)
        val scale = 1f + trap.pulse * 0.18f
        paint.style = Paint.Style.FILL
        paint.color = if (trap.pulse > 0f) Color.rgb(255, 244, 202) else Color.rgb(202, 210, 192)
        val spikeWidth = tileSize * 0.16f
        for (i in -1..1) {
            val center = x + i * spikeWidth * 1.35f
            val spike = Path()
            spike.moveTo(center - spikeWidth * 0.5f, y + tileSize * 0.22f)
            spike.lineTo(center, y - tileSize * 0.22f * scale)
            spike.lineTo(center + spikeWidth * 0.5f, y + tileSize * 0.22f)
            spike.close()
            canvas.drawPath(spike, paint)
        }
    }

    private fun drawTower(canvas: Canvas, tower: Tower, selected: Boolean) {
        val x = cellCenterX(tower.col)
        val y = cellCenterY(tower.row)
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(90, 0, 0, 0)
        canvas.drawOval(x - tileSize * 0.30f, y + tileSize * 0.23f, x + tileSize * 0.30f, y + tileSize * 0.39f, paint)

        if (selected) {
            strokePaint.style = Paint.Style.STROKE
            strokePaint.strokeWidth = tileSize * 0.055f
            strokePaint.color = Color.WHITE
            canvas.drawCircle(x, y, tileSize * 0.39f, strokePaint)
        }

        when (tower.kind) {
            TowerKind.BOLT -> drawBoltTower(canvas, x, y, tower)
            TowerKind.FROST -> drawFrostTower(canvas, x, y, tower)
            TowerKind.CANNON -> drawCannonTower(canvas, x, y, tower)
        }

        for (level in 0 until tower.level) {
            paint.color = tower.kind.accent
            canvas.drawCircle(
                x + (level - 1) * tileSize * 0.11f,
                y + tileSize * 0.34f,
                tileSize * 0.035f,
                paint
            )
        }
    }

    private fun drawBoltTower(canvas: Canvas, x: Float, y: Float, tower: Tower) {
        paint.color = Color.rgb(28, 41, 33)
        canvas.drawRoundRect(x - tileSize * 0.29f, y - tileSize * 0.12f, x + tileSize * 0.29f, y + tileSize * 0.30f, tileSize * 0.07f, tileSize * 0.07f, paint)
        paint.color = Color.rgb(190, 244, 78)
        canvas.drawRect(x - tileSize * 0.22f, y + tileSize * 0.17f, x + tileSize * 0.22f, y + tileSize * 0.25f, paint)
        canvas.save()
        canvas.rotate((tower.angle * 57.29578f), x, y)
        paint.color = Color.rgb(63, 82, 66)
        canvas.drawRoundRect(x - tileSize * 0.06f, y - tileSize * 0.12f, x + tileSize * (0.34f - tower.recoil * 0.06f), y + tileSize * 0.12f, tileSize * 0.05f, tileSize * 0.05f, paint)
        paint.color = tower.kind.accent
        canvas.drawRect(x + tileSize * 0.21f, y - tileSize * 0.07f, x + tileSize * (0.39f - tower.recoil * 0.06f), y + tileSize * 0.07f, paint)
        canvas.restore()
        paint.color = Color.rgb(17, 26, 21)
        canvas.drawCircle(x, y, tileSize * 0.17f, paint)
        paint.color = tower.kind.accent
        canvas.drawCircle(x, y, tileSize * 0.08f, paint)
    }

    private fun drawFrostTower(canvas: Canvas, x: Float, y: Float, tower: Tower) {
        paint.color = Color.rgb(31, 53, 58)
        canvas.drawRoundRect(x - tileSize * 0.30f, y + tileSize * 0.12f, x + tileSize * 0.30f, y + tileSize * 0.31f, tileSize * 0.06f, tileSize * 0.06f, paint)
        val pulse = 1f + sin(ambientTime * 4f + tower.col) * 0.06f
        val crystal = Path()
        crystal.moveTo(x, y - tileSize * 0.36f * pulse)
        crystal.lineTo(x + tileSize * 0.20f, y)
        crystal.lineTo(x + tileSize * 0.10f, y + tileSize * 0.20f)
        crystal.lineTo(x - tileSize * 0.10f, y + tileSize * 0.20f)
        crystal.lineTo(x - tileSize * 0.20f, y)
        crystal.close()
        paint.color = tower.kind.accent
        canvas.drawPath(crystal, paint)
        paint.color = Color.rgb(218, 251, 255)
        val shine = Path()
        shine.moveTo(x - tileSize * 0.05f, y - tileSize * 0.24f)
        shine.lineTo(x + tileSize * 0.04f, y - tileSize * 0.08f)
        shine.lineTo(x - tileSize * 0.08f, y + tileSize * 0.02f)
        shine.close()
        canvas.drawPath(shine, paint)
    }

    private fun drawCannonTower(canvas: Canvas, x: Float, y: Float, tower: Tower) {
        paint.color = Color.rgb(58, 48, 40)
        canvas.drawRoundRect(x - tileSize * 0.32f, y - tileSize * 0.18f, x + tileSize * 0.32f, y + tileSize * 0.31f, tileSize * 0.08f, tileSize * 0.08f, paint)
        paint.color = Color.rgb(255, 164, 75)
        canvas.drawRect(x - tileSize * 0.24f, y + tileSize * 0.19f, x + tileSize * 0.24f, y + tileSize * 0.27f, paint)
        canvas.save()
        canvas.rotate(tower.angle * 57.29578f, x, y)
        paint.color = Color.rgb(40, 35, 31)
        canvas.drawRoundRect(x - tileSize * 0.07f, y - tileSize * 0.14f, x + tileSize * (0.41f - tower.recoil * 0.08f), y + tileSize * 0.14f, tileSize * 0.06f, tileSize * 0.06f, paint)
        paint.color = tower.kind.accent
        canvas.drawRect(x + tileSize * (0.30f - tower.recoil * 0.08f), y - tileSize * 0.17f, x + tileSize * (0.39f - tower.recoil * 0.08f), y + tileSize * 0.17f, paint)
        canvas.restore()
        paint.color = Color.rgb(25, 24, 21)
        canvas.drawCircle(x, y, tileSize * 0.21f, paint)
        paint.color = tower.kind.accent
        canvas.drawCircle(x, y, tileSize * 0.085f, paint)
    }

    private fun drawEnemy(canvas: Canvas, enemy: Enemy) {
        if (!enemy.alive) {
            return
        }
        val x = gridX(enemy.x)
        val bob = sin(enemy.animation) * tileSize * 0.035f
        val y = gridY(enemy.y) + bob
        val size = tileSize * enemy.kind.scale
        val healthRatio = max(0f, enemy.health / enemy.kind.maxHealth)

        paint.style = Paint.Style.FILL
        paint.color = Color.argb(95, 0, 0, 0)
        canvas.drawOval(x - size * 0.32f, y + size * 0.31f, x + size * 0.32f, y + size * 0.43f, paint)

        val bodyColor = if (enemy.flashTimer > 0f) Color.WHITE else enemy.kind.color
        paint.color = darker(bodyColor, 0.72f)
        val legOffset = sin(enemy.animation * 1.3f) * size * 0.08f
        canvas.drawRect(x - size * 0.26f, y + size * 0.16f, x - size * 0.04f, y + size * 0.38f + legOffset, paint)
        canvas.drawRect(x + size * 0.04f, y + size * 0.16f, x + size * 0.26f, y + size * 0.38f - legOffset, paint)

        paint.color = bodyColor
        canvas.drawRoundRect(x - size * 0.34f, y - size * 0.14f, x + size * 0.34f, y + size * 0.23f, size * 0.07f, size * 0.07f, paint)
        paint.color = lighter(bodyColor, 1.16f)
        canvas.drawRoundRect(x - size * 0.29f, y - size * 0.39f, x + size * 0.29f, y - size * 0.04f, size * 0.06f, size * 0.06f, paint)

        paint.color = Color.rgb(18, 25, 21)
        val eyeY = y - size * 0.22f
        canvas.drawRect(x - size * 0.16f, eyeY, x - size * 0.07f, eyeY + size * 0.08f, paint)
        canvas.drawRect(x + size * 0.07f, eyeY, x + size * 0.16f, eyeY + size * 0.08f, paint)
        if (enemy.kind == EnemyKind.OVERGROWTH) {
            paint.color = Color.rgb(255, 207, 90)
            canvas.drawRect(x - size * 0.15f, eyeY, x - size * 0.07f, eyeY + size * 0.06f, paint)
            canvas.drawRect(x + size * 0.07f, eyeY, x + size * 0.15f, eyeY + size * 0.06f, paint)
        }

        if (enemy.slowTimer > 0f) {
            strokePaint.style = Paint.Style.STROKE
            strokePaint.strokeWidth = max(1.5f, tileSize * 0.025f)
            strokePaint.color = Color.rgb(93, 220, 255)
            canvas.drawCircle(x, y, size * 0.48f, strokePaint)
        }

        if (healthRatio < 0.995f || enemy.kind == EnemyKind.OVERGROWTH) {
            val barWidth = size * 0.78f
            val barY = y - size * 0.52f
            paint.color = Color.argb(190, 13, 19, 16)
            canvas.drawRoundRect(x - barWidth * 0.5f, barY, x + barWidth * 0.5f, barY + max(3f, tileSize * 0.065f), tileSize * 0.03f, tileSize * 0.03f, paint)
            paint.color = if (healthRatio > 0.35f) Color.rgb(190, 244, 78) else Color.rgb(255, 91, 84)
            canvas.drawRoundRect(x - barWidth * 0.5f, barY, x - barWidth * 0.5f + barWidth * healthRatio, barY + max(3f, tileSize * 0.065f), tileSize * 0.03f, tileSize * 0.03f, paint)
        }
    }

    private fun drawProjectile(canvas: Canvas, projectile: Projectile) {
        val x = gridX(projectile.x)
        val y = gridY(projectile.y)
        val radius = when (projectile.kind) {
            TowerKind.BOLT -> tileSize * 0.07f
            TowerKind.FROST -> tileSize * 0.09f
            TowerKind.CANNON -> tileSize * 0.14f
        }
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(55, Color.red(projectile.kind.accent), Color.green(projectile.kind.accent), Color.blue(projectile.kind.accent))
        canvas.drawCircle(x, y, radius * 2.2f, paint)
        paint.color = projectile.kind.accent
        canvas.drawCircle(x, y, radius, paint)
        if (projectile.kind == TowerKind.FROST) {
            paint.color = Color.WHITE
            canvas.drawCircle(x - radius * 0.25f, y - radius * 0.25f, radius * 0.28f, paint)
        }
    }

    private fun drawParticle(canvas: Canvas, particle: Particle) {
        val alphaRatio = max(0f, particle.life / particle.maxLife)
        val color = Color.argb(
            (alphaRatio * 220f).toInt(),
            Color.red(particle.color),
            Color.green(particle.color),
            Color.blue(particle.color)
        )
        paint.style = Paint.Style.FILL
        paint.color = color
        val x = gridX(particle.x)
        val y = gridY(particle.y)
        val size = max(2f, particle.size * tileSize * alphaRatio)
        if (particle.square) {
            canvas.drawRect(x - size, y - size, x + size, y + size, paint)
        } else {
            canvas.drawCircle(x, y, size, paint)
        }
    }

    private fun drawTopBar(canvas: Canvas) {
        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(13, 22, 17)
        canvas.drawRect(0f, 0f, viewWidth, topBarHeight, paint)
        paint.color = Color.rgb(190, 244, 78)
        canvas.drawRect(0f, topBarHeight - max(2f, dp(2f)), viewWidth, topBarHeight, paint)

        drawRoundedRect(canvas, dp(10f), dp(8f), dp(48f), topBarHeight - dp(8f), dp(10f), Color.rgb(190, 244, 78))
        drawCenteredText(canvas, "B", dp(29f), topBarHeight * 0.5f, min(dp(21f), topBarHeight * 0.42f), Color.rgb(13, 22, 17), true, true)
        drawText(canvas, "BLOCKHOLD", dp(58f), topBarHeight * 0.43f, min(dp(14f), topBarHeight * 0.27f), Color.WHITE, Paint.Align.LEFT, true, true)
        drawText(canvas, phaseLabel(), dp(58f), topBarHeight * 0.72f, min(dp(9f), topBarHeight * 0.18f), Color.rgb(139, 157, 144), Paint.Align.LEFT, true)

        val statStart = min(viewWidth * 0.31f, dp(300f))
        drawStat(canvas, statStart, "BLOCKS", gold.toString(), Color.rgb(190, 244, 78))
        drawStat(canvas, statStart + min(dp(108f), viewWidth * 0.13f), "CORE", "$lives/10", Color.rgb(255, 111, 100))
        drawStat(canvas, statStart + min(dp(205f), viewWidth * 0.25f), "WAVE", "$waveNumber/$MAX_WAVES", Color.rgb(93, 220, 255))

        if (waveNumber == 0 && (phase == GamePhase.DIG || phase == GamePhase.BUILD)) {
            drawTopButton(canvas, resetPathRect, "RESET", Color.rgb(36, 50, 40), Color.rgb(214, 224, 216), true)
        }
        val canStart = phase == GamePhase.BUILD && pathComplete
        val actionLabel = when (phase) {
            GamePhase.DIG -> "CONNECT CORE"
            GamePhase.BUILD -> "START WAVE"
            GamePhase.WAVE -> "WAVE LIVE"
            else -> "STANDBY"
        }
        drawTopButton(
            canvas,
            primaryActionRect,
            actionLabel,
            if (canStart) Color.rgb(190, 244, 78) else Color.rgb(31, 44, 35),
            if (canStart) Color.rgb(13, 22, 17) else Color.rgb(104, 123, 110),
            canStart
        )
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
            strokePaint.style = Paint.Style.STROKE
            strokePaint.strokeWidth = max(1f, dp(1f))
            strokePaint.color = Color.argb(55, 255, 255, 255)
            canvas.drawRoundRect(rect, dp(10f), dp(10f), strokePaint)
        }
        drawCenteredText(canvas, label, rect.centerX(), rect.centerY(), min(dp(10f), rect.height() * 0.28f), foreground, true)
    }

    private fun phaseLabel(): String {
        return when (phase) {
            GamePhase.DIG -> "PATH FORGE"
            GamePhase.BUILD -> "BUILD PHASE"
            GamePhase.WAVE -> "DEFENSE ACTIVE"
            GamePhase.PAUSED -> "RUN PAUSED"
            else -> "PATHFORGE DEFENSE"
        }
    }

    private fun drawBottomBar(canvas: Canvas) {
        val top = viewHeight - bottomBarHeight
        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(13, 22, 17)
        canvas.drawRect(0f, top, viewWidth, viewHeight, paint)
        paint.color = Color.rgb(32, 47, 37)
        canvas.drawRect(0f, top, viewWidth, top + max(1f, dp(1f)), paint)

        if (selectedTower != null && phase == GamePhase.BUILD) {
            drawTowerPanel(canvas)
        } else {
            drawToolBar(canvas)
        }
    }

    private fun drawToolBar(canvas: Canvas) {
        for (entry in toolRects) {
            val tool = entry.first
            val rect = entry.second
            val selected = selectedTool == tool
            val enabled = phase != GamePhase.WAVE && !(tool == BuildTool.DIG && pathComplete)
            val background = when {
                selected -> Color.rgb(190, 244, 78)
                enabled -> Color.rgb(25, 38, 30)
                else -> Color.rgb(19, 29, 23)
            }
            val foreground = if (selected) Color.rgb(12, 21, 16) else if (enabled) Color.WHITE else Color.rgb(78, 94, 83)
            drawRoundedRect(canvas, rect.left, rect.top, rect.right, rect.bottom, dp(12f), background)
            drawToolIcon(canvas, tool, rect.centerX(), rect.top + rect.height() * 0.37f, min(rect.width(), rect.height()) * 0.25f, foreground)
            drawCenteredText(canvas, tool.title, rect.centerX(), rect.top + rect.height() * 0.67f, min(dp(10f), rect.height() * 0.14f), foreground, true)
            val costText = if (tool.cost == 0) if (pathComplete) "LOCKED" else "DRAW PATH" else "${tool.cost} BLOCKS"
            val costColor = if (tool.cost > gold && tool.cost > 0) Color.rgb(255, 111, 100) else if (selected) Color.rgb(44, 65, 38) else Color.rgb(119, 139, 125)
            drawCenteredText(canvas, costText, rect.centerX(), rect.top + rect.height() * 0.87f, min(dp(8f), rect.height() * 0.11f), costColor, true)
        }
    }

    private fun drawToolIcon(canvas: Canvas, tool: BuildTool, x: Float, y: Float, size: Float, color: Int) {
        paint.style = Paint.Style.FILL
        paint.color = color
        when (tool) {
            BuildTool.DIG -> {
                strokePaint.style = Paint.Style.STROKE
                strokePaint.strokeWidth = max(2f, size * 0.20f)
                strokePaint.strokeCap = Paint.Cap.ROUND
                strokePaint.color = color
                canvas.drawLine(x - size * 0.35f, y + size * 0.35f, x + size * 0.30f, y - size * 0.30f, strokePaint)
                canvas.drawLine(x + size * 0.05f, y - size * 0.35f, x + size * 0.42f, y - size * 0.04f, strokePaint)
            }
            BuildTool.BOLT -> {
                canvas.drawCircle(x, y, size * 0.45f, paint)
                paint.color = if (color == Color.WHITE) Color.rgb(25, 38, 30) else Color.rgb(12, 21, 16)
                canvas.drawCircle(x, y, size * 0.18f, paint)
            }
            BuildTool.FROST -> {
                val diamond = Path()
                diamond.moveTo(x, y - size * 0.55f)
                diamond.lineTo(x + size * 0.40f, y)
                diamond.lineTo(x, y + size * 0.55f)
                diamond.lineTo(x - size * 0.40f, y)
                diamond.close()
                canvas.drawPath(diamond, paint)
            }
            BuildTool.CANNON -> {
                canvas.drawRoundRect(x - size * 0.44f, y - size * 0.25f, x + size * 0.42f, y + size * 0.25f, size * 0.12f, size * 0.12f, paint)
                canvas.drawCircle(x - size * 0.20f, y + size * 0.32f, size * 0.17f, paint)
            }
            BuildTool.SPIKES -> {
                for (i in -1..1) {
                    val center = x + i * size * 0.32f
                    val spike = Path()
                    spike.moveTo(center - size * 0.15f, y + size * 0.35f)
                    spike.lineTo(center, y - size * 0.42f)
                    spike.lineTo(center + size * 0.15f, y + size * 0.35f)
                    spike.close()
                    canvas.drawPath(spike, paint)
                }
            }
        }
    }

    private fun drawTowerPanel(canvas: Canvas) {
        val tower = selectedTower ?: return
        drawRoundedRect(canvas, backRect.left, backRect.top, backRect.right, backRect.bottom, dp(12f), Color.rgb(25, 38, 30))
        drawCenteredText(canvas, "BACK", backRect.centerX(), backRect.centerY(), dp(11f), Color.WHITE, true)

        val upgradeCost = tower.upgradeCost()
        val maxed = tower.level >= 3
        val canBuy = !maxed && gold >= upgradeCost
        val upgradeColor = if (canBuy) tower.kind.accent else Color.rgb(27, 42, 33)
        drawRoundedRect(canvas, upgradeRect.left, upgradeRect.top, upgradeRect.right, upgradeRect.bottom, dp(12f), upgradeColor)
        val titleColor = if (canBuy) Color.rgb(12, 21, 16) else Color.rgb(150, 168, 156)
        val upgradeTitle = if (maxed) "${tower.kind.title.toUpperCase()}  MAX LEVEL" else "UPGRADE ${tower.kind.title.toUpperCase()}  •  LEVEL ${tower.level}"
        drawCenteredText(canvas, upgradeTitle, upgradeRect.centerX(), upgradeRect.top + upgradeRect.height() * 0.38f, min(dp(11f), upgradeRect.width() * 0.033f), titleColor, true)
        val stats = "DMG ${tower.currentDamage().toInt()}   RANGE ${oneDecimal(tower.currentRange())}   ${if (maxed) "COMPLETE" else "$upgradeCost BLOCKS"}"
        drawCenteredText(canvas, stats, upgradeRect.centerX(), upgradeRect.top + upgradeRect.height() * 0.70f, min(dp(9f), upgradeRect.width() * 0.027f), titleColor, true)

        drawRoundedRect(canvas, sellRect.left, sellRect.top, sellRect.right, sellRect.bottom, dp(12f), Color.rgb(50, 37, 32))
        drawCenteredText(canvas, "RECYCLE", sellRect.centerX(), sellRect.top + sellRect.height() * 0.40f, dp(10f), Color.rgb(255, 188, 126), true)
        drawCenteredText(canvas, "+${tower.sellValue()}", sellRect.centerX(), sellRect.top + sellRect.height() * 0.70f, dp(9f), Color.rgb(197, 154, 112), true)
    }

    private fun drawBanner(canvas: Canvas) {
        val instruction = when {
            bannerTimer > 0f -> bannerText
            phase == GamePhase.DIG -> "DRAG ONE BLOCK AT A TIME  •  BACKTRACK TO UNDO  •  MAX $MAX_PATH_LENGTH"
            phase == GamePhase.BUILD && waveNumber == 0 && towers.isEmpty() -> "CHOOSE A DEFENSE BELOW  •  TAP A FREE BLOCK TO BUILD"
            phase == GamePhase.BUILD -> "FORTIFY THE ROUTE  •  TAP A TOWER TO UPGRADE  •  START WHEN READY"
            phase == GamePhase.WAVE -> "TOWERS ARE AUTONOMOUS  •  KEEP THE CORE ALIVE"
            else -> ""
        }
        if (instruction.isEmpty()) {
            return
        }
        val alpha = if (bannerTimer in 0f..0.35f) (bannerTimer / 0.35f * 220f).toInt() else 220
        val width = min(viewWidth * 0.64f, dp(560f))
        val height = min(dp(28f), tileSize * 0.48f)
        val left = (viewWidth - width) * 0.5f
        val top = boardTop + dp(7f)
        drawRoundedRect(canvas, left, top, left + width, top + height, height * 0.45f, Color.argb(alpha, 14, 23, 18))
        drawCenteredText(canvas, instruction, viewWidth * 0.5f, top + height * 0.52f, min(dp(9f), height * 0.34f), Color.argb(min(255, alpha + 25), 224, 232, 226), true)
    }

    private fun drawPauseOverlay(canvas: Canvas) {
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(218, 7, 13, 10)
        canvas.drawRect(0f, 0f, viewWidth, viewHeight, paint)
        drawCenteredText(canvas, "RUN PAUSED", viewWidth * 0.5f, viewHeight * 0.34f, min(dp(43f), viewHeight * 0.10f), Color.WHITE, true, true)
        drawCenteredText(canvas, "YOUR PATH AND DEFENSE ARE SAFE", viewWidth * 0.5f, viewHeight * 0.44f, dp(12f), Color.rgb(153, 171, 159), true)
        drawEndButtons(canvas, "RESUME", "MAIN MENU")
    }

    private fun drawEndOverlay(canvas: Canvas) {
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(226, 7, 13, 10)
        canvas.drawRect(0f, 0f, viewWidth, viewHeight, paint)
        val victory = phase == GamePhase.VICTORY
        val accent = if (victory) Color.rgb(190, 244, 78) else Color.rgb(255, 102, 92)
        drawCenteredText(canvas, if (victory) "CORE SECURED" else "CORE BREACHED", viewWidth * 0.5f, viewHeight * 0.29f, min(dp(48f), viewHeight * 0.105f), accent, true, true)
        drawCenteredText(
            canvas,
            if (victory) "THE OVERGROWTH HAS BEEN BROKEN" else "REBUILD THE PATH  ADAPT THE LINE  TRY AGAIN",
            viewWidth * 0.5f,
            viewHeight * 0.39f,
            dp(12f),
            Color.rgb(184, 198, 188),
            true
        )

        val cardWidth = min(dp(370f), viewWidth * 0.58f)
        val cardLeft = (viewWidth - cardWidth) * 0.5f
        val cardTop = viewHeight * 0.47f
        val cardBottom = viewHeight * 0.62f
        drawRoundedRect(canvas, cardLeft, cardTop, cardLeft + cardWidth, cardBottom, dp(14f), Color.rgb(20, 32, 25))
        drawResultStat(canvas, cardLeft + cardWidth * 0.18f, cardTop, cardBottom, "SCORE", score.toString(), accent)
        drawResultStat(canvas, cardLeft + cardWidth * 0.50f, cardTop, cardBottom, "WAVES", "$waveNumber/$MAX_WAVES", Color.rgb(93, 220, 255))
        drawResultStat(canvas, cardLeft + cardWidth * 0.82f, cardTop, cardBottom, "BEST", bestScore.toString(), Color.rgb(255, 188, 96))
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
}
