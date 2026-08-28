package ai.techtroy.blockhold

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import java.util.Calendar
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
        /** 1.4 F0 Wide Hold — larger forge grid. */
        private const val COLS = 16
        private const val ROWS = 9
        private const val START_ROW = 4
        private const val MAX_PATH_LENGTH = 64
        private const val STARTING_BLOCKS = 420
        private const val STARTING_CORE = 12
        private const val MIN_CAMERA_ZOOM = 0.72f
        private const val MAX_CAMERA_ZOOM = 2.15f
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

    /**
     * SharedPreferences.getInt/getBoolean throw ClassCastException when an older build stored the
     * same key with a different type. These reads happen in the constructor, so an unguarded throw
     * takes the whole activity down before the first frame and the app simply "won't open" for
     * anyone upgrading from an earlier version. Always fall back to the default instead.
     */
    private fun prefInt(key: String, fallback: Int): Int =
        try { preferences.getInt(key, fallback) } catch (_: Exception) { fallback }

    private fun prefBoolean(key: String, fallback: Boolean): Boolean =
        try { preferences.getBoolean(key, fallback) } catch (_: Exception) { fallback }

    /**
     * Read from the package rather than hard-coded, so the title screen can never disagree with
     * the manifest again. The 1.4 content shipped under a hard-coded "v1.2" label, which is how
     * an entire era of work ended up invisible in the build it was in.
     */
    private val versionLabel: String = try {
        val name = context.packageManager.getPackageInfo(context.packageName, 0).versionName
        if (name.isNullOrBlank()) "" else "v$name"
    } catch (_: Exception) {
        ""
    }

    @Volatile private var running = false
    @Volatile private var activityPaused = false
    private var renderThread: Thread? = null

    private var phase = GamePhase.TITLE
    private var phaseBeforePause = GamePhase.BUILD
    private var selectedTool = BuildTool.DIG
    private var selectedTower: Tower? = null
    private var selectedTrap: SpikeTrap? = null
    private var selectedCorruption: CorruptedCell? = null
    private var selectedUtility: Utility? = null
    private var selectedUtilityKind: UtilityKind? = null
    private var selectedStoredTrapIndex = -1
    private var evolutionTower: Tower? = null
    private var imbuementTower: Tower? = null
    private var imbuementTrap: SpikeTrap? = null
    private var imbuementUtility: Utility? = null
    private var buildPage = BuildPage.TOWERS
    private var utilityPageIndex = 0
    private var towerPageIndex = 0
    private var cachePageIndex = 0
    private var workshopTab = WorkshopTab.CRAFT
    private var workshopPageIndex = 0

    private val pathCells = ArrayList<GridCell>()
    private val reforgeOriginalPath = ArrayList<GridCell>()
    private val towers = ArrayList<Tower>()
    private val traps = ArrayList<SpikeTrap>()
    private val utilities = ArrayList<Utility>()
    private val storedTraps = ArrayList<StoredTrap>()
    private val supplies = HashMap<CraftedItem, Int>()
    private val corruptions = ArrayList<CorruptedCell>()
    private val perks = HashMap<ForgePerk, Int>()
    private val perkChoices = ArrayList<ForgePerk>()
    private val enemies = ArrayList<Enemy>()
    private val pendingSpawns = ArrayList<Enemy>()
    private val projectiles = ArrayList<Projectile>()
    private val particles = ArrayList<Particle>()
    private val impactEffects = ArrayList<ImpactFx>()
    private val floatingLabels = ArrayList<FloatingLabel>()
    private val waveQueue = ArrayList<SpawnSpec>()

    private var gold = STARTING_BLOCKS
    private var maxCore = STARTING_CORE
    private var lives = STARTING_CORE
    private var score = 0
    private var bestScore = prefInt("best_score", 0)
    private var bestWave = prefInt("best_wave", 0)
    private var bestDailyScore = prefInt("best_daily_score", prefInt("best_challenge_score", 0))
    private var bestDailyWave = prefInt("best_daily_wave", prefInt("best_challenge_wave", 0))
    private var bestCustomScore = prefInt("best_custom_score", prefInt("best_challenge_score", 0))
    private var bestCustomWave = prefInt("best_custom_wave", prefInt("best_challenge_wave", 0))
    private var waveNumber = 0
    private var spawnIndex = 0
    private var spawnTimer = 0f
    private var nextEnemyId = 1
    private var nextTrapId = 1
    private var nextCorruptionId = 1
    private var forgeCharges = 0
    private var evolutionCores = 0
    private var salvageParts = 0
    private var growthEssence = 0
    private var surveyLensWaves = 0
    private var phaseBarrierReady = false
    /** Crafts C1: next multi-damage core hit loses 1 damage. */
    private var splinterBraceReady = false
    private var routeOilWaves = 0
    private var scrapMagnetKills = 0

    /** Crafts C1: brief Hex immunity per tower cell key. */
    private var coolingImmunity = HashMap<Int, Float>()
    private var reforgeCost = 0
    private var gameMode = GameMode.ENDLESS
    private var challengeModifier = ChallengeModifier.NONE
    private var runSeed = 7331L
    private val challengeDigits = intArrayOf(7, 3, 3, 1, 0, 1)
    private var pathComplete = false
    private var ambientTime = 0f
    private var bannerText = ""
    private var bannerTimer = 0f
    private var bannerDuration = 2.4f
    private var screenShake = 0f
    private var diggingGesture = false
    private var waveTheme = "FORGE THE FIRST ROUTE"
    private var goldPulse = 0f
    private var forgePulse = 0f
    private var lastDisplayedGold = -1
    private var lastDisplayedForge = -1
    private var savedRunAvailable = prefBoolean("has_saved_run", false)

    private var viewWidth = 1f
    private var viewHeight = 1f
    private var density = resources.displayMetrics.density
    private var topBarHeight = 1f
    private var bottomBarHeight = 1f
    private var boardLeft = 0f
    private var boardTop = 0f
    private var tileSize = 1f
    /** Fit-to-viewport tile size before camera zoom (F0). */
    private var baseTileSize = 1f
    private var viewportLeft = 0f
    private var viewportTop = 0f
    private var viewportRight = 1f
    private var viewportBottom = 1f
    private var cameraZoom = 1f
    private var cameraPanX = 0f
    private var cameraPanY = 0f
    private var cameraGesture = false
    private var panLastX = 0f
    private var panLastY = 0f
    private var pinchActive = false
    private var pinchStartDistance = 1f
    private var pinchStartZoom = 1f
    private var pinchFocusX = 0f
    private var pinchFocusY = 0f
    private var pinchStartPanX = 0f
    private var pinchStartPanY = 0f
    private var suppressGridTap = false

    private val primaryActionRect = RectF()
    private val resetPathRect = RectF()
    private val pauseRect = RectF()
    private val soundRect = RectF()
    private val titlePlayRect = RectF()
    private val titleContinueRect = RectF()
    private val titleChallengeRect = RectF()
    private val titleSoundRect = RectF()
    private val endPrimaryRect = RectF()
    private val endSecondaryRect = RectF()
    private val upgradeRect = RectF()
    private val storeRect = RectF()
    private val imbueRect = RectF()
    private val sellRect = RectF()
    private val backRect = RectF()
    private val towerPageRect = RectF()
    private val trapPageRect = RectF()
    private val utilityPageRect = RectF()
    private val cachePageRect = RectF()
    private val challengeDailyRect = RectF()
    private val challengeSeedStartRect = RectF()
    private val challengeBackRect = RectF()
    private val seedDigitRects = ArrayList<RectF>()
    private val perkRects = ArrayList<RectF>()
    private val evolutionRects = ArrayList<RectF>()
    private val toolRects = ArrayList<Pair<BuildTool, RectF>>()
    private val utilityRects = ArrayList<Pair<UtilityKind, RectF>>()
    private val cacheRects = ArrayList<Pair<Int, RectF>>()
    private val workshopTabRects = ArrayList<Pair<WorkshopTab, RectF>>()
    private val workshopCardRects = ArrayList<RectF>()
    private val workshopBackRect = RectF()
    private val workshopPreviousRect = RectF()
    private val workshopNextRect = RectF()

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
            if (phase == GamePhase.WAVE || phase == GamePhase.BUILD || phase == GamePhase.DIG || phase == GamePhase.REFORGE || phase == GamePhase.PERK_DRAFT || phase == GamePhase.EVOLUTION_DRAFT || phase == GamePhase.WORKSHOP) {
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
                GamePhase.CHALLENGE_MENU -> phase = GamePhase.TITLE
                GamePhase.REFORGE -> cancelReforge()
                GamePhase.WORKSHOP -> closeWorkshop()
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
        viewportLeft = dp(10f)
        viewportRight = viewWidth - dp(10f)
        viewportTop = topBarHeight + dp(4f)
        viewportBottom = viewHeight - bottomBarHeight - dp(6f)
        val verticalSpace = max(40f, viewportBottom - viewportTop)
        val horizontalSpace = max(40f, viewportRight - viewportLeft)
        baseTileSize = min(horizontalSpace / COLS, verticalSpace / ROWS)
        baseTileSize = max(14f, baseTileSize)
        applyCameraTransform()

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
        val pageWidth = min(dp(112f), totalWidth * 0.17f)
        val pageGap = dp(4f)
        val tabWidth = (pageWidth - pageGap) * 0.5f
        val tabHeight = (toolBottom - toolTop - pageGap) * 0.5f
        towerPageRect.set(left, toolTop, left + tabWidth, toolTop + tabHeight)
        trapPageRect.set(towerPageRect.right + pageGap, toolTop, left + pageWidth, toolTop + tabHeight)
        utilityPageRect.set(left, towerPageRect.bottom + pageGap, left + tabWidth, toolBottom)
        cachePageRect.set(utilityPageRect.right + pageGap, trapPageRect.bottom + pageGap, left + pageWidth, toolBottom)
        rebuildToolRects(left + pageWidth + dp(7f), totalWidth - pageWidth - dp(7f), toolTop, toolBottom)

        val panelWidth = min(viewWidth - dp(24f), dp(650f))
        val panelLeft = (viewWidth - panelWidth) * 0.5f
        val panelTop = viewHeight - bottomBarHeight + dp(12f)
        val panelBottom = viewHeight - dp(12f)
        val panelGap = dp(8f)
        backRect.set(panelLeft, panelTop, panelLeft + panelWidth * 0.15f, panelBottom)
        upgradeRect.set(backRect.right + panelGap, panelTop, backRect.right + panelGap + panelWidth * 0.47f, panelBottom)
        val actionLeft = upgradeRect.right + panelGap
        val actionHeight = (panelBottom - panelTop - panelGap) / 3f
        storeRect.set(actionLeft, panelTop, panelLeft + panelWidth, panelTop + actionHeight)
        imbueRect.set(actionLeft, storeRect.bottom + panelGap * 0.5f, panelLeft + panelWidth, storeRect.bottom + panelGap * 0.5f + actionHeight)
        sellRect.set(actionLeft, imbueRect.bottom + panelGap * 0.5f, panelLeft + panelWidth, panelBottom)

        val titleButtonWidth = min(dp(230f), viewWidth * 0.34f)
        val titleButtonHeight = min(dp(60f), viewHeight * 0.13f)
        titlePlayRect.set(viewWidth * 0.5f - titleButtonWidth - dp(6f), viewHeight * 0.68f, viewWidth * 0.5f - dp(6f), viewHeight * 0.68f + titleButtonHeight)
        titleContinueRect.set(viewWidth * 0.5f + dp(6f), viewHeight * 0.68f, viewWidth * 0.5f + titleButtonWidth + dp(6f), viewHeight * 0.68f + titleButtonHeight)
        val challengeHeight = min(dp(39f), viewHeight * 0.085f)
        titleChallengeRect.set(viewWidth * 0.5f - titleButtonWidth * 0.72f, viewHeight * 0.835f, viewWidth * 0.5f + titleButtonWidth * 0.72f, viewHeight * 0.835f + challengeHeight)
        titleSoundRect.set(viewWidth - dp(58f), dp(14f), viewWidth - dp(14f), dp(58f))

        computeOverlayRects()

        val endButtonWidth = min(dp(230f), viewWidth * 0.34f)
        val endButtonHeight = min(dp(58f), viewHeight * 0.13f)
        endPrimaryRect.set(viewWidth * 0.5f - endButtonWidth - dp(6f), viewHeight * 0.70f, viewWidth * 0.5f - dp(6f), viewHeight * 0.70f + endButtonHeight)
        endSecondaryRect.set(viewWidth * 0.5f + dp(6f), viewHeight * 0.70f, viewWidth * 0.5f + endButtonWidth + dp(6f), viewHeight * 0.70f + endButtonHeight)
    }

    private fun computeOverlayRects() {
        val cardGap = dp(10f)
        val cardWidth = min(dp(220f), (viewWidth - dp(48f) - cardGap * 2f) / 3f)
        val total = cardWidth * 3f + cardGap * 2f
        val left = (viewWidth - total) * 0.5f
        val top = viewHeight * 0.34f
        val bottom = viewHeight * 0.70f
        perkRects.clear()
        repeat(3) { index -> perkRects.add(RectF(left + index * (cardWidth + cardGap), top, left + index * (cardWidth + cardGap) + cardWidth, bottom)) }

        val evolutionWidth = min(dp(285f), (viewWidth - dp(42f) - cardGap) / 2f)
        val evolutionLeft = (viewWidth - evolutionWidth * 2f - cardGap) * 0.5f
        evolutionRects.clear()
        repeat(2) { index -> evolutionRects.add(RectF(evolutionLeft + index * (evolutionWidth + cardGap), top, evolutionLeft + index * (evolutionWidth + cardGap) + evolutionWidth, bottom)) }

        val challengeWidth = min(dp(300f), viewWidth * 0.38f)
        val challengeTop = viewHeight * 0.27f
        val challengeBottom = viewHeight * 0.54f
        challengeDailyRect.set(viewWidth * 0.5f - challengeWidth - dp(7f), challengeTop, viewWidth * 0.5f - dp(7f), challengeBottom)
        challengeSeedStartRect.set(viewWidth * 0.5f + dp(7f), challengeTop, viewWidth * 0.5f + challengeWidth + dp(7f), challengeBottom)
        challengeBackRect.set(viewWidth * 0.5f - dp(90f), viewHeight * 0.82f, viewWidth * 0.5f + dp(90f), viewHeight * 0.82f + min(dp(45f), viewHeight * 0.10f))
        seedDigitRects.clear()
        val digitSize = min(dp(45f), viewWidth * 0.055f)
        val digitGap = dp(7f)
        val digitTotal = digitSize * 6f + digitGap * 5f
        val digitLeft = (viewWidth - digitTotal) * 0.5f
        repeat(6) { index -> seedDigitRects.add(RectF(digitLeft + index * (digitSize + digitGap), viewHeight * 0.63f, digitLeft + index * (digitSize + digitGap) + digitSize, viewHeight * 0.63f + digitSize)) }

        workshopBackRect.set(dp(16f), dp(14f), dp(92f), dp(54f))
        workshopTabRects.clear()
        val tabTotal = min(dp(420f), viewWidth * 0.58f)
        val tabLeft = (viewWidth - tabTotal) * 0.5f
        val tabGap = dp(7f)
        val tabWidth = (tabTotal - tabGap * 2f) / 3f
        WorkshopTab.values().forEachIndexed { index, tab ->
            workshopTabRects.add(Pair(tab, RectF(tabLeft + index * (tabWidth + tabGap), viewHeight * 0.14f, tabLeft + index * (tabWidth + tabGap) + tabWidth, viewHeight * 0.23f)))
        }
        workshopCardRects.clear()
        val workshopGap = dp(10f)
        val workshopWidth = min(dp(620f), viewWidth - dp(48f))
        val workshopLeft = (viewWidth - workshopWidth) * 0.5f
        val workshopCardWidth = (workshopWidth - workshopGap) * 0.5f
        val workshopTop = viewHeight * 0.29f
        val workshopBottom = viewHeight * 0.75f
        val workshopCardHeight = (workshopBottom - workshopTop - workshopGap) * 0.5f
        repeat(4) { index ->
            val column = index % 2
            val row = index / 2
            val cardLeft = workshopLeft + column * (workshopCardWidth + workshopGap)
            val cardTop = workshopTop + row * (workshopCardHeight + workshopGap)
            workshopCardRects.add(RectF(cardLeft, cardTop, cardLeft + workshopCardWidth, cardTop + workshopCardHeight))
        }
        workshopPreviousRect.set(viewWidth * 0.5f - dp(120f), viewHeight * 0.80f, viewWidth * 0.5f - dp(12f), viewHeight * 0.89f)
        workshopNextRect.set(viewWidth * 0.5f + dp(12f), viewHeight * 0.80f, viewWidth * 0.5f + dp(120f), viewHeight * 0.89f)
    }

    private fun rebuildToolRects(left: Float = cachePageRect.right + dp(7f), width: Float = min(viewWidth - dp(20f), dp(790f)) - (cachePageRect.right - towerPageRect.left) - dp(7f), top: Float = viewHeight - bottomBarHeight + dp(9f), bottom: Float = viewHeight - dp(9f)) {
        toolRects.clear()
        utilityRects.clear()
        cacheRects.clear()
        val gap = dp(6f)
        when (buildPage) {
            BuildPage.TOWERS, BuildPage.TRAPS -> {
                val tools = visibleTools()
                val buttonWidth = (width - gap * (tools.size - 1)) / tools.size
                var x = left
                for (tool in tools) {
                    toolRects.add(Pair(tool, RectF(x, top, x + buttonWidth, bottom)))
                    x += buttonWidth + gap
                }
            }
            BuildPage.UTILITIES -> {
                val kinds = UtilityKind.values().drop(utilityPageIndex * 4).take(4)
                val buttonWidth = (width - gap * (kinds.size - 1)) / max(1, kinds.size)
                var x = left
                for (kind in kinds) {
                    utilityRects.add(Pair(kind, RectF(x, top, x + buttonWidth, bottom)))
                    x += buttonWidth + gap
                }
            }
            BuildPage.CACHE -> {
                val start = cachePageIndex * 5
                val indices = (start until min(storedTraps.size, start + 5)).toList()
                val count = max(1, indices.size)
                val buttonWidth = (width - gap * (count - 1)) / count
                var x = left
                for (index in indices) {
                    cacheRects.add(Pair(index, RectF(x, top, x + buttonWidth, bottom)))
                    x += buttonWidth + gap
                }
            }
        }
    }

    private fun visibleTools(): Array<BuildTool> {
        return when (buildPage) {
            BuildPage.TOWERS -> {
                val all = arrayOf(
                    BuildTool.BOLT, BuildTool.FROST, BuildTool.CANNON, BuildTool.EMBER,
                    BuildTool.BEACON, BuildTool.THORN, BuildTool.LANCE, BuildTool.MIRE,
                    BuildTool.GALE, BuildTool.SUNFORGE, BuildTool.LODESTONE,
                    BuildTool.HOWL, BuildTool.VITRIOL,
                    BuildTool.GRAVEBOLT, BuildTool.AEGIS_LOOM
                )
                all.drop(towerPageIndex * 4).take(4).toTypedArray()
            }
            BuildPage.TRAPS -> arrayOf(BuildTool.SPIKES, BuildTool.ROOT, BuildTool.RUNE, BuildTool.ARC, BuildTool.CRUSHER)
            else -> emptyArray()
        }
    }

    private fun update(delta: Float) {
        ambientTime += delta
        if (bannerTimer > 0f) bannerTimer -= delta
        goldPulse = max(0f, goldPulse - delta * 2.8f)
        forgePulse = max(0f, forgePulse - delta * 2.8f)
        if (lastDisplayedGold >= 0 && gold > lastDisplayedGold) goldPulse = 1f
        if (lastDisplayedForge >= 0 && forgeCharges > lastDisplayedForge) forgePulse = 1f
        lastDisplayedGold = gold
        lastDisplayedForge = forgeCharges
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
                val rush = waveNumber % 8 == 2 || waveNumber % 8 == 1 || challengeModifier == ChallengeModifier.RUSH_HOUR
                spawnTimer = if (rush) 0.40f else max(0.46f, 0.82f - waveNumber * 0.008f)
            }
        }
        updateCorruptions(delta)
        for (utility in utilities) utility.disabledTimer = max(0f, utility.disabledTimer - delta)

        for (enemy in enemies) {
            if (!enemy.alive) continue
            if (enemy.dying) {
                enemy.deathTimer -= delta
                enemy.flashTimer = max(0f, enemy.flashTimer - delta)
                enemy.animation += delta * 6f
                if (enemy.deathTimer <= 0f) {
                    enemy.deathTimer = 0f
                    enemy.alive = false
                }
                continue
            }
            enemy.animation += delta * (4f + enemy.moveSpeed * 2f)
            enemy.flashTimer = max(0f, enemy.flashTimer - delta)
            enemy.slowTimer = max(0f, enemy.slowTimer - delta)
            enemy.markTimer = max(0f, enemy.markTimer - delta)
            enemy.graveMarkTimer = max(0f, enemy.graveMarkTimer - delta)
            if (enemy.graveMarkTimer <= 0f) enemy.graveMarkDamage = 0f
            enemy.armorShredTimer = max(0f, enemy.armorShredTimer - delta)
            if (enemy.armorShredTimer <= 0f) enemy.armorShred = 0f
            enemy.rootTimer = max(0f, enemy.rootTimer - delta)
            enemy.stunTimer = max(0f, enemy.stunTimer - delta)
            enemy.armoredTimer = max(0f, enemy.armoredTimer - delta)
            enemy.gloomTimer = max(0f, enemy.gloomTimer - delta)
            enemy.wispTimer = max(0f, enemy.wispTimer - delta)
            enemy.thornArmorTimer = max(0f, enemy.thornArmorTimer - delta)
            enemy.pyreTrailTimer = max(0f, enemy.pyreTrailTimer - delta)
            if (enemy.windupTimer > 0f) {
                enemy.windupTimer -= delta
                if (enemy.windupTimer <= 0f) {
                    resolveEnemyWindup(enemy)
                }
            }
            enemy.abilityTimer -= delta

            if (enemy.burnTimer > 0f) {
                enemy.burnTimer -= delta
                damageEnemy(enemy, enemy.burnDamagePerSecond * delta, Color.rgb(255, 104, 55), 0.45f, false)
                if (perkCount(ForgePerk.SPREADING_EMBERS) > 0) {
                    val spread = enemy.burnDamagePerSecond * (0.10f + perkCount(ForgePerk.SPREADING_EMBERS) * 0.04f) * delta
                    enemies.filter { it.targetable && it !== enemy && abs(it.progress - enemy.progress) < 0.85f }.take(1 + perkCount(ForgePerk.SPREADING_EMBERS)).forEach {
                        damageEnemy(it, spread, Color.rgb(255, 104, 55), 0.4f, false)
                        it.burnTimer = max(it.burnTimer, 0.35f)
                    }
                }
            }
            // F2 Pyre Wight: scorched trail burns nearby path enemies
            if (enemy.pyreTrailTimer > 0f && enemy.kind == EnemyKind.PYRE_WIGHT) {
                enemies.filter { it.targetable && it !== enemy && abs(it.progress - enemy.progress) < 0.55f }.take(2).forEach {
                    ignite(it, it.maxHealth * 0.012f, 0.8f)
                }
            }
            val regeneration = enemy.kind.regeneration + if (waveNumber % 8 == 4) 0.006f else 0f
            if (regeneration > 0f && enemy.health > 0f && enemy.health < enemy.maxHealth) enemy.health = min(enemy.maxHealth, enemy.health + enemy.maxHealth * regeneration * delta)
            if (!enemy.alive || enemy.dying) continue
            updateEnemyAbility(enemy)

            val slowMultiplier = when {
                enemy.slowTimer <= 0f -> 1f
                enemy.kind == EnemyKind.NEEDLEFLY -> 0.82f  // F1: resists most slow
                enemy.kind == EnemyKind.DRIFT_SEED -> 0.90f // F5: almost ignores slow (floats)
                else -> 0.56f
            }
            var gateSlow = 1f
            if (routeOilWaves > 0 && enemy.progress < 4.5f) gateSlow = 0.55f
            val wardenSlow = pathWardenSlow(enemy.x, enemy.y)
            if (enemy.stunTimer <= 0f) enemy.progress += enemy.moveSpeed * slowMultiplier * gateSlow * wardenSlow * delta
            updateEnemyPosition(enemy)
            applyCorruptionToEnemy(enemy, delta)
            triggerTrapIfNeeded(enemy)
            if (!enemy.alive) continue

            if (enemy.progress >= pathCells.size - 1f) {
                enemy.alive = false
                if (phaseBarrierReady) {
                    phaseBarrierReady = false
                    splinterBraceReady = false
                    coolingImmunity.clear()
                    floatingLabels.add(FloatingLabel("BARRIER", enemy.x, enemy.y, Color.rgb(93, 220, 255)))
                    burst(enemy.x, enemy.y, Color.rgb(93, 220, 255), 22, 1.3f)
                } else {
                    var coreDamage = enemy.kind.baseDamage + if (enemy.bossTier >= 3) 1 else 0
                    if (splinterBraceReady && coreDamage >= 2) {
                        coreDamage -= 1
                        splinterBraceReady = false
                        floatingLabels.add(FloatingLabel("BRACED", enemy.x, enemy.y - 0.35f, Color.rgb(190, 244, 78), pop = 1.35f))
                        burst(enemy.x, enemy.y, Color.rgb(190, 244, 78), 14, 0.9f)
                    }
                    lives = max(0, lives - coreDamage)
                    floatingLabels.add(FloatingLabel("-$coreDamage CORE", enemy.x, enemy.y, Color.rgb(255, 107, 96)))
                }
                audio.play("base_hit", 0.75f, if (enemy.kind.boss) 0.72f else 1f)
                screenShake = 0.35f
                burst(enemy.x, enemy.y, Color.rgb(255, 91, 84), 18, 1.4f)
                if (lives <= 0) {
                    finishRun(false)
                    return
                }
            }
        }

        for (tower in towers) {
            tower.cooldown -= delta
            tower.recoil = max(0f, tower.recoil - delta * 4.2f)
            tower.evolveFlash = max(0f, tower.evolveFlash - delta * 2.4f)
            tower.evolveAura = max(0f, tower.evolveAura - delta)
            tower.evolveProof = max(0f, tower.evolveProof - delta)
            tower.damageBoostTimer = max(0f, tower.damageBoostTimer - delta)
            tower.focusBoostTimer = max(0f, tower.focusBoostTimer - delta)
            val hexDecay = if (tower.imbuement == Imbuement.WARD) delta * 1.75f else delta
            tower.disabledTimer = max(0f, tower.disabledTimer - hexDecay)
            val immKey = tower.col * 100 + tower.row
            val imm = coolingImmunity[immKey] ?: 0f
            if (imm > 0f) {
                val left = imm - delta
                if (left <= 0f) coolingImmunity.remove(immKey) else coolingImmunity[immKey] = left
            }
            if (tower.disabledTimer > 0f) continue
            val target = findTarget(tower)
            if (target != null) {
                tower.angle = atan2(target.y - (tower.row + 0.5f), target.x - (tower.col + 0.5f))
                if (tower.cooldown <= 0f) {
                    fireTower(tower, target)
                    var interval = tower.currentInterval()
                    if (hasHarmonyNear(tower)) interval *= 0.80f
                    interval *= sparkRelayIntervalMul(tower.col, tower.row)
                    if (perkCount(ForgePerk.LAST_BASTION) > 0 && distanceSquared(tower.col + 0.5f, tower.row + 0.5f, COLS - 0.5f, START_ROW + 0.5f) <= 14f) interval *= max(0.55f, 1f - perkCount(ForgePerk.LAST_BASTION) * 0.10f)
                    tower.cooldown = interval
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

    private fun updateCorruptions(delta: Float) {
        for (corruption in corruptions) {
            if (corruption.kind != CorruptionKind.HEX_BLOOM) continue
            corruption.cooldown -= delta
            if (corruption.cooldown > 0f) continue
            val towerTarget = towers.minByOrNull { distanceSquared(it.col + 0.5f, it.row + 0.5f, corruption.cell.col + 0.5f, corruption.cell.row + 0.5f) }
            val utilityTarget = utilities.minByOrNull { distanceSquared(it.col + 0.5f, it.row + 0.5f, corruption.cell.col + 0.5f, corruption.cell.row + 0.5f) }
            val towerDistance = if (towerTarget == null) Float.MAX_VALUE else distanceSquared(towerTarget.col + 0.5f, towerTarget.row + 0.5f, corruption.cell.col + 0.5f, corruption.cell.row + 0.5f)
            val utilityDistance = if (utilityTarget == null) Float.MAX_VALUE else distanceSquared(utilityTarget.col + 0.5f, utilityTarget.row + 0.5f, corruption.cell.col + 0.5f, corruption.cell.row + 0.5f)
            if (min(towerDistance, utilityDistance) <= 12f) {
                if (towerDistance <= utilityDistance && towerTarget != null) {
                    val clarity = if (towerTarget.imbuement == Imbuement.CLARITY) 0.25f else 1f
                    applyTowerHex(towerTarget, (if (hasHarmonyNear(towerTarget)) 0.8f else 1.8f) * clarity)
                    floatingLabels.add(FloatingLabel("HEX BLOOM", towerTarget.col + 0.5f, towerTarget.row + 0.3f, corruption.kind.accent))
                } else if (utilityTarget != null) {
                    utilityTarget.disabledTimer = max(utilityTarget.disabledTimer, 1.8f * if (utilityTarget.imbuement == Imbuement.CLARITY) 0.25f else 1f)
                    floatingLabels.add(FloatingLabel("HEX BLOOM", utilityTarget.col + 0.5f, utilityTarget.row + 0.3f, corruption.kind.accent))
                }
            }
            corruption.cooldown = 5.8f
        }
    }

    private fun applyCorruptionToEnemy(enemy: Enemy, delta: Float) {
        val pathIndex = min(max(0, (enemy.progress + 0.20f).toInt()), pathCells.size - 1)
        val cell = pathCells[pathIndex]
        for (corruption in corruptions) {
            if (corruption.cell != cell) continue
            when (corruption.kind) {
                CorruptionKind.SPORE_PATH -> enemy.health = min(enemy.maxHealth, enemy.health + enemy.maxHealth * 0.020f * delta)
                CorruptionKind.CARAPACE_GROWTH -> enemy.armoredTimer = max(enemy.armoredTimer, 0.45f)
                CorruptionKind.BLINK_ROOT -> if (enemy.triggeredCorruptions.add(corruption.id)) {
                    enemy.progress = min(pathCells.size - 1.05f, enemy.progress + 0.60f)
                    burst(enemy.x, enemy.y, corruption.kind.accent, 9, 0.7f)
                }
                else -> Unit
            }
        }
    }

    private fun updateEnemyAbility(enemy: Enemy) {
        if (enemy.abilityTimer > 0f || enemy.windupTimer > 0f) return
        when (enemy.kind) {
            EnemyKind.BLINK_STALKER -> {
                enemy.progress = min(pathCells.size - 1.05f, enemy.progress + 0.85f)
                enemy.abilityTimer = 4.3f
                burst(enemy.x, enemy.y, enemy.kind.color, 10, 0.8f)
            }
            EnemyKind.ROOTCALLER -> {
                for (ally in enemies) {
                    if (!ally.alive) continue
                    if (distanceSquared(ally.x, ally.y, enemy.x, enemy.y) <= 4.4f) ally.health = min(ally.maxHealth, ally.health + ally.maxHealth * 0.055f)
                }
                enemy.abilityTimer = 4.8f
                burst(enemy.x, enemy.y, Color.rgb(91, 196, 99), 12, 0.75f)
            }
            EnemyKind.HEX_WEAVER -> {
                val target = towers.filter { it.disabledTimer <= 0f }.minByOrNull { distanceSquared(it.col + 0.5f, it.row + 0.5f, enemy.x, enemy.y) }
                if (target != null && distanceSquared(target.col + 0.5f, target.row + 0.5f, enemy.x, enemy.y) <= 10f) {
                    applyTowerHex(target, (if (hasHarmonyNear(target)) 1.2f else 2.8f) * if (target.imbuement == Imbuement.CLARITY) 0.25f else 1f)
                    floatingLabels.add(FloatingLabel("HEXED", target.col + 0.5f, target.row + 0.35f, enemy.kind.color))
                }
                enemy.abilityTimer = 5.7f
            }
            EnemyKind.GLOOMKIN -> {
                enemy.gloomTimer = max(enemy.gloomTimer, 2.4f)
                enemy.abilityTimer = 5.2f
                floatingLabels.add(FloatingLabel("GLOOM", enemy.x, enemy.y - 0.3f, enemy.kind.color, 0.6f))
                burst(enemy.x, enemy.y, enemy.kind.color, 8, 0.55f)
            }
            EnemyKind.WISP_DRIFTER -> {
                enemy.wispTimer = max(enemy.wispTimer, 2.0f)
                enemy.abilityTimer = 4.6f
                floatingLabels.add(FloatingLabel("WISP", enemy.x, enemy.y - 0.3f, enemy.kind.color, 0.55f))
                burst(enemy.x, enemy.y, enemy.kind.color, 7, 0.5f)
            }
            EnemyKind.GRAVE_MENDER -> {
                beginEnemyWindup(enemy, 1, 1.15f, "MENDING…")
                enemy.abilityTimer = 6.2f
            }
            EnemyKind.PYRE_WIGHT -> {
                beginEnemyWindup(enemy, 2, 0.95f, "PYRE…")
                enemy.abilityTimer = 5.4f
            }
            EnemyKind.OVERGROWTH -> {
                beginEnemyWindup(enemy, 3, 1.25f, "OVERGROWTH SURGE")
                enemy.abilityTimer = max(3.4f, 6.8f - enemy.bossTier * 0.25f)
            }
            EnemyKind.IRON_MONARCH -> {
                beginEnemyWindup(enemy, 4, 1.45f, "MONARCH SLAM")
                enemy.abilityTimer = max(3.8f, 7.2f - enemy.bossTier * 0.2f)
            }
            EnemyKind.SPORE_SOVEREIGN -> {
                beginEnemyWindup(enemy, 5, 1.35f, "SPORE BLOOM")
                enemy.abilityTimer = max(3.6f, 6.5f - enemy.bossTier * 0.22f)
            }
            EnemyKind.VEIN_LURKER -> {
                beginEnemyWindup(enemy, 6, 1.05f, "DRAIN…")
                enemy.abilityTimer = 5.8f
            }
            EnemyKind.MIRROR_MOTH -> {
                enemy.mirrorCharges = max(enemy.mirrorCharges, 2)
                enemy.abilityTimer = 6.5f
                floatingLabels.add(FloatingLabel("MIRROR", enemy.x, enemy.y - 0.3f, enemy.kind.color, 0.6f))
                burst(enemy.x, enemy.y, enemy.kind.color, 10, 0.7f)
            }
            EnemyKind.TIDAL_ROOT -> {
                beginEnemyWindup(enemy, 7, 1.50f, "TIDAL SURGE")
                enemy.abilityTimer = max(4.0f, 7.4f - enemy.bossTier * 0.2f)
            }
            EnemyKind.ASHEN_CHOIR -> {
                beginEnemyWindup(enemy, 8, 1.40f, "ASHEN HYMN")
                enemy.abilityTimer = max(3.8f, 7.0f - enemy.bossTier * 0.22f)
            }
            else -> enemy.abilityTimer = 8f
        }
    }

    private fun beginEnemyWindup(enemy: Enemy, kind: Int, duration: Float, tell: String) {
        enemy.windupKind = kind
        enemy.windupTimer = duration
        floatingLabels.add(FloatingLabel(tell, enemy.x, enemy.y - 0.45f, enemy.kind.color, pop = 1.25f))
        burst(enemy.x, enemy.y, enemy.kind.color, if (enemy.kind.boss) 14 else 8, if (enemy.kind.boss) 1.0f else 0.65f)
        if (enemy.kind.boss || enemy.kind.elite) {
            setBanner(tell, if (enemy.kind.boss) 1.8f else 1.2f)
            audio.play("wave", 0.35f, if (enemy.kind.boss) 0.7f else 1.15f)
        }
    }

    private fun resolveEnemyWindup(enemy: Enemy) {
        if (!enemy.alive || enemy.dying) {
            enemy.windupKind = 0
            return
        }
        when (enemy.windupKind) {
            1 -> { // Grave Mender
                for (ally in enemies) {
                    if (!ally.targetable) continue
                    if (distanceSquared(ally.x, ally.y, enemy.x, enemy.y) <= 6.5f) {
                        ally.health = min(ally.maxHealth, ally.health + ally.maxHealth * 0.12f)
                    }
                }
                floatingLabels.add(FloatingLabel("MENDED", enemy.x, enemy.y - 0.25f, enemy.kind.color, 0.7f))
                burst(enemy.x, enemy.y, enemy.kind.color, 16, 1.0f)
            }
            2 -> { // Pyre Wight
                enemy.pyreTrailTimer = 3.2f
                enemy.burnTimer = max(enemy.burnTimer, 2.5f)
                enemy.burnDamagePerSecond = max(enemy.burnDamagePerSecond, enemy.maxHealth * 0.02f)
                towers.filter { distanceSquared(it.col + 0.5f, it.row + 0.5f, enemy.x, enemy.y) <= 3.5f }.forEach {
                    applyTowerHex(it, (if (hasHarmonyNear(it)) 0.6f else 1.4f) * if (it.imbuement == Imbuement.CLARITY) 0.25f else 1f)
                }
                floatingLabels.add(FloatingLabel("IGNITED", enemy.x, enemy.y - 0.25f, enemy.kind.color, 0.75f))
                burst(enemy.x, enemy.y, enemy.kind.color, 18, 1.1f)
            }
            3 -> { // Overgrowth
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
                    towers.sortedBy { distanceSquared(it.col + 0.5f, it.row + 0.5f, enemy.x, enemy.y) }.take(min(3, enemy.bossTier - 1)).forEach {
                        applyTowerHex(it, (if (hasHarmonyNear(it)) 0.9f else 2.2f) * if (it.imbuement == Imbuement.CLARITY) 0.25f else 1f)
                    }
                }
                burst(enemy.x, enemy.y, Color.rgb(103, 220, 94), 18, 1.1f)
            }
            4 -> { // Iron Monarch slam
                val radius = 5.5f + enemy.bossTier * 0.4f
                towers.filter { distanceSquared(it.col + 0.5f, it.row + 0.5f, enemy.x, enemy.y) <= radius * radius }.forEach {
                    applyTowerHex(it, (if (hasHarmonyNear(it)) 1.1f else 2.6f) * if (it.imbuement == Imbuement.CLARITY) 0.25f else 1f)
                }
                enemies.filter { it.targetable && it !== enemy && distanceSquared(it.x, it.y, enemy.x, enemy.y) <= 4f }.forEach {
                    it.progress = min(pathCells.size - 1.05f, it.progress + 0.15f)
                }
                screenShake = max(screenShake, 0.45f)
                floatingLabels.add(FloatingLabel("SLAM", enemy.x, enemy.y - 0.3f, enemy.kind.color, pop = 1.4f))
                burst(enemy.x, enemy.y, Color.rgb(255, 180, 80), 28, 1.5f)
                audio.play("cannon", 0.45f, 0.75f)
            }
            5 -> { // Spore Sovereign bloom
                enemy.health = min(enemy.maxHealth, enemy.health + enemy.maxHealth * 0.04f)
                repeat(min(4, 2 + enemy.bossTier / 2)) {
                    val minion = Enemy(nextEnemyId++, EnemyKind.MYCELIAL, enemy.healthScale * 0.14f, min(1.4f, enemy.speedScale * 1.1f), 0.4f, splitDepth = 1)
                    minion.progress = max(0f, enemy.progress - it * 0.22f)
                    updateEnemyPosition(minion)
                    pendingSpawns.add(minion)
                }
                enemies.filter { it.targetable && it !== enemy && distanceSquared(it.x, it.y, enemy.x, enemy.y) <= 5.5f }.forEach {
                    it.health = min(it.maxHealth, it.health + it.maxHealth * 0.06f)
                }
                floatingLabels.add(FloatingLabel("BLOOM", enemy.x, enemy.y - 0.3f, enemy.kind.color, pop = 1.35f))
                burst(enemy.x, enemy.y, enemy.kind.color, 26, 1.4f)
                audio.play("build", 0.4f, 0.65f)
            }
            6 -> { // Vein Lurker drain
                for (ally in enemies) {
                    if (!ally.targetable || ally === enemy) continue
                    if (distanceSquared(ally.x, ally.y, enemy.x, enemy.y) <= 5.0f) {
                        val siphon = ally.maxHealth * 0.04f
                        ally.health = max(1f, ally.health - siphon)
                        enemy.health = min(enemy.maxHealth, enemy.health + siphon * 1.5f)
                    }
                }
                floatingLabels.add(FloatingLabel("DRAINED", enemy.x, enemy.y - 0.25f, enemy.kind.color, 0.75f))
                burst(enemy.x, enemy.y, enemy.kind.color, 14, 0.95f)
            }
            7 -> { // Tidal Root flood
                for (ally in enemies) {
                    if (!ally.targetable) continue
                    if (distanceSquared(ally.x, ally.y, enemy.x, enemy.y) <= 7.5f) {
                        ally.slowTimer = 0f // wash slows? no - enemies get speed pulse
                        ally.progress = min(pathCells.size - 1.05f, ally.progress + 0.35f)
                    }
                }
                towers.filter { distanceSquared(it.col + 0.5f, it.row + 0.5f, enemy.x, enemy.y) <= 5.5f }.forEach {
                    applyTowerHex(it, (if (hasHarmonyNear(it)) 0.8f else 1.8f) * if (it.imbuement == Imbuement.CLARITY) 0.25f else 1f)
                }
                floatingLabels.add(FloatingLabel("FLOOD", enemy.x, enemy.y - 0.35f, enemy.kind.color, pop = 1.4f))
                burst(enemy.x, enemy.y, enemy.kind.color, 28, 1.5f)
                audio.play("frost", 0.5f, 0.55f)
            }
            8 -> { // Ashen Choir hymn
                towers.sortedBy { distanceSquared(it.col + 0.5f, it.row + 0.5f, enemy.x, enemy.y) }.take(min(4, 2 + enemy.bossTier)).forEach {
                    applyTowerHex(it, (if (hasHarmonyNear(it)) 1.0f else 2.4f) * if (it.imbuement == Imbuement.CLARITY) 0.25f else 1f)
                    if (it.imbuement != Imbuement.WARD) it.disabledTimer = max(it.disabledTimer, 0.9f)
                }
                floatingLabels.add(FloatingLabel("HYMN", enemy.x, enemy.y - 0.35f, enemy.kind.color, pop = 1.4f))
                burst(enemy.x, enemy.y, enemy.kind.color, 24, 1.35f)
                audio.play("beacon", 0.45f, 0.7f)
            }
        }
        enemy.windupKind = 0
        enemy.windupTimer = 0f
    }

    private fun spawnEnemy(spec: SpawnSpec) {
        if (pathCells.size < 2) return
        val challengeSpeed = if (challengeModifier == ChallengeModifier.RUSH_HOUR) 1.18f else 1f
        val enemy = Enemy(nextEnemyId++, spec.kind, spec.healthScale, spec.speedScale * challengeSpeed, spec.rewardScale, spec.bossTier, spec.splitDepth)
        // F5 Hollow Shell: temporary armor shell absorbs first hits
        if (spec.kind == EnemyKind.HOLLOW_SHELL) {
            enemy.shellBuffer = enemy.maxHealth * 0.45f
        }
        if (spec.kind == EnemyKind.MIRROR_MOTH) {
            enemy.mirrorCharges = 3
        }
        updateEnemyPosition(enemy)
        enemies.add(enemy)
        // F2 banner sting on named elite/boss spawn
        if (spec.kind.boss) {
            setBanner(spec.kind.title.uppercase() + "  TIER ${max(1, spec.bossTier)}", 2.6f)
            audio.play("wave", 0.55f, 0.72f)
            burst(enemy.x, enemy.y, spec.kind.color, 16, 1.1f)
        } else if (spec.kind.elite && (spec.kind == EnemyKind.GRAVE_MENDER || spec.kind == EnemyKind.PYRE_WIGHT || spec.kind == EnemyKind.THORNBACK || spec.kind == EnemyKind.VEIN_LURKER || spec.kind == EnemyKind.MIRROR_MOTH)) {
            setBanner("ELITE  ${spec.kind.title.uppercase()}", 1.6f)
            burst(enemy.x, enemy.y, spec.kind.color, 10, 0.8f)
        }
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
            if (trap.col != cell.col || trap.row != cell.row) continue
            if (trap.jamTimer > 0f) continue
            val beaconRelay = isNearTowerKind(trap.col, trap.row, TowerKind.BEACON)
            val maxTriggers = 1 + perkCount(ForgePerk.DOUBLE_TRIGGER) + if (beaconRelay) 1 else 0
            val triggers = enemy.trapTriggerCounts[trap.id] ?: 0
            if (triggers >= maxTriggers) continue
            // F1 Sapper: after 2 trap hits this life, skip further traps
            if (enemy.kind == EnemyKind.SAPPER && enemy.sapperTraps >= 2) continue
            enemy.trapTriggerCounts[trap.id] = triggers + 1
            if (enemy.kind == EnemyKind.SAPPER) enemy.sapperTraps += 1
            // F5 Briar Mite: jams the trap after trigger
            if (enemy.kind == EnemyKind.BRIAR_MITE) {
                trap.jamTimer = max(trap.jamTimer, 3.2f)
                floatingLabels.add(FloatingLabel("JAMMED", trap.col + 0.5f, trap.row + 0.2f, enemy.kind.color, 0.55f))
            }
            trap.activationCount += 1
            if (trap.surgeCharges > 0) trap.surgeCharges -= 1
            trap.pulse = 1f
            var damage = effectiveTrapDamage(trap)
            val tempoStatus = if (trap.imbuement == Imbuement.TEMPO) 1.22f else 1f
            when (trap.kind) {
                TrapKind.SPIKE -> {
                    damageEnemy(enemy, damage, trap.kind.accent)
                    if (perkCount(ForgePerk.CONDUCTIVE_SPIKES) > 0) enemies.filter { it.targetable && it !== enemy }.minByOrNull { abs(it.progress - enemy.progress) }?.let { damageEnemy(it, damage * 0.38f, TrapKind.ARC.accent, 0.5f) }
                    if (isNearTowerKind(trap.col, trap.row, TowerKind.EMBER)) ignite(enemy, damage * 0.16f, 2.4f)
                }
                TrapKind.ROOT -> {
                    damageEnemy(enemy, damage, trap.kind.accent)
                    enemy.slowTimer = max(enemy.slowTimer, (2.2f + trap.level * 0.25f) * tempoStatus)
                    enemy.rootTimer = max(enemy.rootTimer, 2.4f * tempoStatus)
                    if (perkCount(ForgePerk.BURNING_ROOTS) > 0 || isNearTowerKind(trap.col, trap.row, TowerKind.EMBER)) ignite(enemy, damage * 0.25f, 3.0f * tempoStatus)
                }
                TrapKind.EMBER -> {
                    damageEnemy(enemy, damage, trap.kind.accent, 0.35f)
                    ignite(enemy, damage * 0.30f, 3.5f * tempoStatus)
                }
                TrapKind.ARC -> {
                    damageEnemy(enemy, damage, trap.kind.accent, 0.55f)
                    enemy.stunTimer = max(enemy.stunTimer, (0.55f + trap.level * 0.10f) * tempoStatus)
                    val stormNetwork = isNearTowerKind(trap.col, trap.row, TowerKind.BEACON)
                    val targets = 2 + perkCount(ForgePerk.EXPANDED_ARC) + if (stormNetwork) 2 else 0
                    enemies.filter { it.targetable && it !== enemy && abs(it.progress - enemy.progress) < (if (stormNetwork) 2.2f else 1.4f) + if (trap.imbuement == Imbuement.REACH) 0.8f else 0f }.take(targets).forEach {
                        damageEnemy(it, damage * if (stormNetwork) 0.70f else 0.55f, trap.kind.accent, 0.65f)
                    }
                }
                TrapKind.CRUSHER -> {
                    if (enemy.slowTimer > 0f && isNearTowerKind(trap.col, trap.row, TowerKind.FROST)) damage *= 1.45f
                    damageEnemy(enemy, damage, trap.kind.accent)
                    enemy.stunTimer = max(enemy.stunTimer, 1.0f * tempoStatus)
                }
            }
            if (trap.imbuement == Imbuement.ECHOES && trap.activationCount % 5 == 0 && enemy.alive) {
                damageEnemy(enemy, damage * 0.70f, Imbuement.ECHOES.accent, 0.35f)
                floatingLabels.add(FloatingLabel("ECHO", enemy.x, enemy.y - 0.3f, Imbuement.ECHOES.accent))
            }
            burst(enemy.x, enemy.y, trap.kind.accent, if (trap.kind == TrapKind.CRUSHER) 15 else 9, 0.9f)
            audio.play("dig", 0.32f, 1.18f + trap.kind.ordinal * 0.04f)
        }
    }

    private fun effectiveTrapDamage(trap: SpikeTrap): Float {
        var damage = trap.currentDamage()
        if (trap.kind == TrapKind.CRUSHER) damage *= 1f + perkCount(ForgePerk.REINFORCED_CRUSHERS) * 0.50f
        if (perkCount(ForgePerk.PATHFINDER_TRAPS) > 0) damage *= 1f + min(0.75f, max(0, pathCells.size - 12) * 0.012f * perkCount(ForgePerk.PATHFINDER_TRAPS))
        if (hasCinderReactorNear(trap.col, trap.row)) damage *= 1.30f
        damage *= trapLatticeBonus(trap.col, trap.row)
        return damage
    }

    private fun findTarget(tower: Tower): Enemy? {
        // targetable excludes dying corpses (1.3 Phase B)
        // F1 Gloomkin: prefer non-stealthed targets when any exist in range
        var best: Enemy? = null
        var bestProgress = -1f
        var bestStealthed: Enemy? = null
        var bestStealthedProgress = -1f
        val centerX = tower.col + 0.5f
        val centerY = tower.row + 0.5f
        val rangeSquared = tower.currentRange() * tower.currentRange()
        for (enemy in enemies) {
            if (!enemy.targetable) continue
            if (distanceSquared(enemy.x, enemy.y, centerX, centerY) > rangeSquared) continue
            if (enemy.stealthed) {
                if (enemy.progress > bestStealthedProgress) {
                    bestStealthed = enemy
                    bestStealthedProgress = enemy.progress
                }
            } else if (enemy.progress > bestProgress) {
                best = enemy
                bestProgress = enemy.progress
            }
        }
        return best ?: bestStealthed
    }

    private fun fireTower(tower: Tower, target: Enemy) {
        var damage = tower.currentDamage()
        if (perkCount(ForgePerk.CORNER_AMBUSH) > 0 && enemyOnCorner(target)) damage *= 1f + perkCount(ForgePerk.CORNER_AMBUSH) * 0.22f
        damage *= battleBannerDamageBonus(tower.col, tower.row)
        if (tower.imbuement == Imbuement.SIEGE && (target.kind.elite || target.kind.boss)) damage *= 1.22f
        if (tower.imbuement == Imbuement.RIME && target.slowTimer > 0.05f) damage *= 1.18f
        tower.activationCount += 1
        if (tower.surgeCharges > 0) tower.surgeCharges -= 1
        val evolveHot = tower.evolveProof > 0.02f || tower.focusBoostTimer > 0f
        projectiles.add(Projectile(tower.col + 0.5f, tower.row + 0.5f, target, tower.kind, damage, tower.kind.projectileSpeed, tower, evolveHot = evolveHot))
        if (tower.imbuement == Imbuement.ECHOES && tower.activationCount % 5 == 0) {
            projectiles.add(Projectile(tower.col + 0.5f, tower.row + 0.5f, target, tower.kind, damage * 0.65f, tower.kind.projectileSpeed * 1.12f, tower, evolveHot = evolveHot))
            floatingLabels.add(FloatingLabel("ECHO", tower.col + 0.5f, tower.row + 0.2f, Imbuement.ECHOES.accent))
        }
        if (tower.imbuement == Imbuement.VOLLEY && tower.activationCount % 4 == 0) {
            val second = enemies.filter { it.targetable && it !== target }.maxByOrNull { it.progress }
            if (second != null) {
                projectiles.add(Projectile(tower.col + 0.5f, tower.row + 0.5f, second, tower.kind, damage * 0.45f, tower.kind.projectileSpeed * 1.05f, tower, evolveHot = evolveHot))
                floatingLabels.add(FloatingLabel("VOLLEY", tower.col + 0.5f, tower.row + 0.2f, Imbuement.VOLLEY.accent))
            }
        }
        if (tower.kind == TowerKind.BOLT && isNearTowerKind(tower.col, tower.row, TowerKind.BEACON, exclude = tower) && random.nextFloat() < 0.22f) {
            enemies.filter { it.targetable && it !== target }.sortedByDescending { it.progress }.firstOrNull()?.let {
                projectiles.add(Projectile(tower.col + 0.5f, tower.row + 0.5f, it, tower.kind, damage * 0.65f, tower.kind.projectileSpeed, tower, evolveHot = evolveHot))
            }
        }
        if (tower.kind == TowerKind.BEACON && perkCount(ForgePerk.RESONANCE_FEEDBACK) > 0 && random.nextFloat() < min(0.60f, perkCount(ForgePerk.RESONANCE_FEEDBACK) * 0.18f)) {
            projectiles.add(Projectile(tower.col + 0.5f, tower.row + 0.5f, target, tower.kind, damage * 0.55f, tower.kind.projectileSpeed * 1.15f, tower, evolveHot = evolveHot))
        }
        when (tower.kind) {
            TowerKind.BOLT -> audio.play("bolt", 0.22f, 0.94f + random.nextFloat() * 0.12f)
            TowerKind.FROST -> audio.play("frost", 0.20f, 0.96f + random.nextFloat() * 0.10f)
            TowerKind.CANNON -> {
                audio.play("cannon", 0.42f, 0.92f + random.nextFloat() * 0.08f)
                screenShake = max(screenShake, 0.09f)
            }
            TowerKind.EMBER -> audio.play("ember", 0.28f, 0.94f + random.nextFloat() * 0.10f)
            TowerKind.BEACON -> audio.play("beacon", 0.26f, 0.96f + random.nextFloat() * 0.10f)
            TowerKind.THORN -> audio.play("bolt", 0.20f, 1.12f + random.nextFloat() * 0.10f)
            TowerKind.LANCE -> audio.play("bolt", 0.28f, 0.78f + random.nextFloat() * 0.08f)
            TowerKind.MIRE -> audio.play("frost", 0.24f, 0.70f + random.nextFloat() * 0.10f)
            // 1.4-era towers reuse existing effect sounds instead of throwing
            // NoWhenBranchMatchedException on their first shot.
            TowerKind.GALE, TowerKind.HOWL, TowerKind.VITRIOL -> audio.play("frost", 0.24f, 0.80f + random.nextFloat() * 0.10f)
            TowerKind.SUNFORGE -> {
                audio.play("ember", 0.30f, 0.90f + random.nextFloat() * 0.08f)
                screenShake = max(screenShake, 0.06f)
            }
            TowerKind.LODESTONE -> audio.play("beacon", 0.26f, 0.90f + random.nextFloat() * 0.10f)
            TowerKind.GRAVEBOLT -> audio.play("bolt", 0.26f, 1.05f + random.nextFloat() * 0.10f)
            TowerKind.AEGIS_LOOM -> audio.play("frost", 0.20f, 0.95f + random.nextFloat() * 0.08f)
        }
    }

    private fun updateProjectiles(delta: Float) {
        for (projectile in projectiles) {
            if (!projectile.alive) continue
            projectile.age += delta
            if (!projectile.target.alive || projectile.target.dying) {
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
        val tower = projectile.source
        spawnImpact(projectile.x, projectile.y, projectile.kind)
        applyBindingFrom(tower, projectile.target)
        if (tower?.imbuement == Imbuement.RIME) {
            projectile.target.slowTimer = max(projectile.target.slowTimer, 1.35f)
        }
        if (projectile.evolveHot) {
            burst(projectile.x, projectile.y, Color.rgb(255, 215, 104), 10, 0.85f)
            burst(projectile.x, projectile.y, projectile.kind.accent, 8, 0.7f)
        }
        when (projectile.kind) {
            TowerKind.BOLT -> {
                val pierce = if (tower.evolution == TowerEvolution.RAIL_SPIRE) 0.82f else 0f
                damageEnemy(projectile.target, projectile.damage, projectile.kind.accent, pierce)
                val chainCount = (if (tower.evolution == TowerEvolution.CHAIN_CONDUCTOR) 3 else 0) + perkCount(ForgePerk.FORKED_BOLTS)
                if (chainCount > 0) enemies.filter { it.targetable && it !== projectile.target && abs(it.progress - projectile.target.progress) < 2.0f }.take(chainCount).forEachIndexed { index, enemy ->
                    damageEnemy(enemy, projectile.damage * max(0.35f, 0.68f - index * 0.10f), projectile.kind.accent, 0.45f)
                    spawnImpact(enemy.x, enemy.y, projectile.kind)
                }
                if (tower.evolution == TowerEvolution.RAIL_SPIRE) enemies.filter { it.targetable && it !== projectile.target && abs(it.progress - projectile.target.progress) < 1.3f }.take(2).forEach {
                    damageEnemy(it, projectile.damage * 0.62f, projectile.kind.accent, 0.82f)
                    spawnImpact(it.x, it.y, projectile.kind)
                }
                if (projectile.target.rootTimer > 0f && isNearTrapKind(tower.col, tower.row, TrapKind.ROOT)) damageEnemy(projectile.target, projectile.damage * 0.35f, Color.rgb(91, 196, 99), 0.4f)
                burst(projectile.x, projectile.y, projectile.kind.accent, 5, 0.55f)
            }
            TowerKind.FROST -> {
                val wasFrozen = projectile.target.slowTimer > 0f
                damageEnemy(projectile.target, projectile.damage * if (wasFrozen && tower.evolution == TowerEvolution.SHATTER_CRYSTAL) 2.15f else 1f, projectile.kind.accent, 0.75f)
                val frostTime = (1.9f + perkCount(ForgePerk.LINGERING_FROST) * 0.75f)
                projectile.target.slowTimer = max(projectile.target.slowTimer, frostTime)
                if (tower.evolution == TowerEvolution.BLIZZARD_LENS) enemies.filter { it.targetable && abs(it.progress - projectile.target.progress) < 1.25f }.forEach { it.slowTimer = max(it.slowTimer, frostTime * 0.8f) }
                if (projectile.target.burnTimer > 0f && isNearTowerKind(tower.col, tower.row, TowerKind.EMBER)) thermalShock(projectile.target, projectile.damage)
                burst(projectile.x, projectile.y, projectile.kind.accent, 8, 0.7f)
            }
            TowerKind.CANNON, TowerKind.EMBER -> {
                var radius = if (projectile.kind == TowerKind.CANNON) 0.9f else 0.72f
                if (projectile.kind == TowerKind.CANNON) radius += perkCount(ForgePerk.CANNON_SHRAPNEL) * 0.14f
                if (tower.evolution == TowerEvolution.SIEGE_MORTAR) radius += 0.55f
                for (enemy in enemies) {
                    if (!enemy.targetable) continue
                    val distance = sqrt(distanceSquared(enemy.x, enemy.y, projectile.x, projectile.y))
                    if (distance <= radius) {
                        val multiplier = (1f - distance * 0.24f) * if (projectile.kind == TowerKind.CANNON) (1f + perkCount(ForgePerk.CANNON_SHRAPNEL) * 0.18f) else 1f
                        damageEnemy(enemy, projectile.damage * multiplier, projectile.kind.accent, if (projectile.kind == TowerKind.EMBER) 0.45f else 0f)
                        if (tower.evolution == TowerEvolution.GRAVITY_CANNON) enemy.progress = max(0f, enemy.progress - 0.32f)
                        if (projectile.kind == TowerKind.EMBER) {
                            val inferno = if (tower.evolution == TowerEvolution.INFERNO_ENGINE) 1.65f else 1f
                            ignite(enemy, projectile.damage * 0.18f * inferno, 2.8f * inferno)
                            if (enemy.slowTimer > 0f && isNearTowerKind(tower.col, tower.row, TowerKind.FROST)) thermalShock(enemy, projectile.damage)
                        }
                    }
                }
                if (projectile.kind == TowerKind.CANNON && isNearTrapKind(tower.col, tower.row, TrapKind.ARC)) enemies.filter { it.targetable }.sortedBy { abs(it.progress - projectile.target.progress) }.take(2).forEach { damageEnemy(it, projectile.damage * 0.28f, TrapKind.ARC.accent, 0.65f) }
                if (projectile.kind == TowerKind.CANNON && isNearTrapKind(tower.col, tower.row, TrapKind.CRUSHER)) resetCrushersNear(tower.col + 0.5f, tower.row + 0.5f)
                burst(projectile.x, projectile.y, projectile.kind.accent, if (projectile.kind == TowerKind.CANNON) 18 else 13, 1.4f)
                screenShake = max(screenShake, if (projectile.kind == TowerKind.CANNON) 0.18f else 0.08f)
            }
            TowerKind.BEACON -> {
                val count = if (tower.evolution == TowerEvolution.STORM_CHOIR) 7 else 4
                val range = if (tower.evolution == TowerEvolution.STORM_CHOIR) 3.2f else 2.1f
                val chain = enemies.filter { it.targetable && abs(it.progress - projectile.target.progress) < range }.sortedBy { abs(it.progress - projectile.target.progress) }.take(count)
                chain.forEachIndexed { index, enemy ->
                    damageEnemy(enemy, projectile.damage * max(0.32f, 1f - index * if (tower.evolution == TowerEvolution.STORM_CHOIR) 0.08f else 0.14f), projectile.kind.accent, 0.70f)
                    burst(enemy.x, enemy.y, projectile.kind.accent, 5, 0.55f)
                    if (index > 0) spawnImpact(enemy.x, enemy.y, projectile.kind)
                }
            }
            TowerKind.THORN -> {
                damageEnemy(projectile.target, projectile.damage, projectile.kind.accent, 0.15f)
                val markDur = if (tower.evolution == TowerEvolution.BRAMBLE_CROWN) 4.2f else 2.8f
                projectile.target.markTimer = max(projectile.target.markTimer, markDur)
                if (tower.evolution == TowerEvolution.VENOM_QUILL) ignite(projectile.target, projectile.damage * 0.12f, 2.0f)
                burst(projectile.x, projectile.y, projectile.kind.accent, 6, 0.55f)
            }
            TowerKind.LANCE -> {
                val pierce = if (tower.evolution == TowerEvolution.PRISM_RAIL) 0.95f else 0.80f
                damageEnemy(projectile.target, projectile.damage, projectile.kind.accent, pierce)
                val rail = if (tower.evolution == TowerEvolution.PRISM_RAIL) 2.4f else 1.6f
                enemies.filter { it.targetable && it !== projectile.target && abs(it.progress - projectile.target.progress) < rail }
                    .sortedBy { abs(it.progress - projectile.target.progress) }
                    .take(if (tower.evolution == TowerEvolution.PRISM_RAIL) 3 else 2)
                    .forEach {
                        damageEnemy(it, projectile.damage * 0.72f, projectile.kind.accent, pierce * 0.9f)
                        spawnImpact(it.x, it.y, projectile.kind)
                    }
                if (tower.evolution == TowerEvolution.SKEWER_ARRAY) {
                    enemies.filter { it.targetable && it !== projectile.target }.sortedByDescending { it.progress }.firstOrNull()?.let {
                        damageEnemy(it, projectile.damage * 0.55f, projectile.kind.accent, 0.75f)
                        spawnImpact(it.x, it.y, projectile.kind)
                    }
                }
                burst(projectile.x, projectile.y, projectile.kind.accent, 8, 0.75f)
            }
            TowerKind.MIRE -> {
                var radius = if (tower.evolution == TowerEvolution.BOG_KING) 1.25f else 0.85f
                for (enemy in enemies) {
                    if (!enemy.targetable) continue
                    val distance = sqrt(distanceSquared(enemy.x, enemy.y, projectile.x, projectile.y))
                    if (distance <= radius) {
                        val mult = 1f - distance * 0.28f
                        damageEnemy(enemy, projectile.damage * mult, projectile.kind.accent, 0.20f)
                        val slow = if (tower.evolution == TowerEvolution.BOG_KING) 2.8f else 1.9f
                        enemy.slowTimer = max(enemy.slowTimer, slow * mult)
                    }
                }
                if (tower.evolution == TowerEvolution.TAR_FONT) {
                    projectile.target.stunTimer = max(projectile.target.stunTimer, 0.55f)
                }
                burst(projectile.x, projectile.y, projectile.kind.accent, 12, 0.95f)
            }
            TowerKind.GALE -> {
                damageEnemy(projectile.target, projectile.damage, projectile.kind.accent, 0.55f)
                val shove = if (tower.evolution == TowerEvolution.CYCLONE_CROWN) 0.55f else 0.32f
                projectile.target.progress = max(0f, projectile.target.progress - shove)
                updateEnemyPosition(projectile.target)
                if (tower.evolution == TowerEvolution.SHEAR_BLADE) {
                    enemies.filter { it.targetable && it !== projectile.target && abs(it.progress - projectile.target.progress) < 1.1f }
                        .minByOrNull { abs(it.progress - projectile.target.progress) }
                        ?.let { secondary ->
                            damageEnemy(secondary, projectile.damage * 0.72f, projectile.kind.accent, 0.45f)
                            secondary.progress = max(0f, secondary.progress - shove * 0.55f)
                            updateEnemyPosition(secondary)
                            spawnImpact(secondary.x, secondary.y, projectile.kind)
                        }
                }
                burst(projectile.x, projectile.y, projectile.kind.accent, 7, 0.65f)
            }
            TowerKind.SUNFORGE -> {
                var dmg = projectile.damage
                if (tower.evolution == TowerEvolution.HEARTH_CORE) {
                    val dist = sqrt(distanceSquared(projectile.target.x, projectile.target.y, tower.col + 0.5f, tower.row + 0.5f))
                    if (dist < 2.4f) dmg *= 1.35f
                }
                damageEnemy(projectile.target, dmg, projectile.kind.accent, 0.40f)
                ignite(projectile.target, dmg * 0.28f, 2.6f)
                if (tower.evolution == TowerEvolution.SOLAR_FLARE) {
                    enemies.filter { it.targetable && it !== projectile.target && abs(it.progress - projectile.target.progress) < 1.15f }
                        .take(2).forEach { near ->
                            damageEnemy(near, dmg * 0.45f, projectile.kind.accent, 0.25f)
                            ignite(near, dmg * 0.18f, 2.0f)
                            spawnImpact(near.x, near.y, projectile.kind)
                        }
                }
                burst(projectile.x, projectile.y, projectile.kind.accent, 8, 0.7f)
            }
            TowerKind.LODESTONE -> {
                damageEnemy(projectile.target, projectile.damage, projectile.kind.accent, 0.50f)
                val yank = if (tower.evolution == TowerEvolution.PULL_WELL) 0.70f else 0.40f
                projectile.target.progress = max(0f, projectile.target.progress - yank)
                updateEnemyPosition(projectile.target)
                if (tower.evolution == TowerEvolution.REPULSOR) {
                    projectile.target.stunTimer = max(projectile.target.stunTimer, 0.65f)
                }
                enemies.filter { it.targetable && it !== projectile.target && abs(it.progress - projectile.target.progress) < 1.0f }
                    .take(2).forEach { near ->
                        near.progress = max(0f, near.progress - yank * 0.35f)
                        updateEnemyPosition(near)
                    }
                burst(projectile.x, projectile.y, projectile.kind.accent, 6, 0.6f)
            }
            TowerKind.HOWL -> {
                damageEnemy(projectile.target, projectile.damage, projectile.kind.accent, 0.35f)
                // Reveal stealth + soft slow
                val reveal = if (tower.evolution == TowerEvolution.ECHO_VAULT) 3.2f else 1.8f
                projectile.target.gloomTimer = 0f
                projectile.target.wispTimer = 0f
                projectile.target.slowTimer = max(projectile.target.slowTimer, if (tower.evolution == TowerEvolution.PACK_CALL) 1.6f else 1.1f)
                if (tower.evolution == TowerEvolution.PACK_CALL) {
                    enemies.filter { it.targetable && it !== projectile.target && abs(it.progress - projectile.target.progress) < 1.2f }
                        .minByOrNull { abs(it.progress - projectile.target.progress) }
                        ?.let { secondary ->
                            damageEnemy(secondary, projectile.damage * 0.55f, projectile.kind.accent, 0.25f)
                            secondary.gloomTimer = 0f
                            secondary.wispTimer = 0f
                            secondary.slowTimer = max(secondary.slowTimer, 1.0f)
                            spawnImpact(secondary.x, secondary.y, projectile.kind)
                        }
                }
                // Keep reveal window via slow-ish flash label
                floatingLabels.add(FloatingLabel("HOWL", projectile.target.x, projectile.target.y - 0.35f, projectile.kind.accent, 0.5f))
                // Echo vault: brief stun pulse as "longer reveal lock"
                if (tower.evolution == TowerEvolution.ECHO_VAULT) {
                    projectile.target.stunTimer = max(projectile.target.stunTimer, 0.35f)
                }
                burst(projectile.x, projectile.y, projectile.kind.accent, 8, 0.7f)
            }
            TowerKind.VITRIOL -> {
                damageEnemy(projectile.target, projectile.damage, projectile.kind.accent, 0.45f)
                val shred = if (tower.evolution == TowerEvolution.ACID_VEIN) 0.42f else 0.28f
                val shredTime = if (tower.evolution == TowerEvolution.ACID_VEIN) 3.4f else 2.4f
                projectile.target.armorShred = max(projectile.target.armorShred, shred)
                projectile.target.armorShredTimer = max(projectile.target.armorShredTimer, shredTime)
                if (tower.evolution == TowerEvolution.RUST_SCOUR) {
                    ignite(projectile.target, projectile.damage * 0.16f, 1.8f)
                }
                floatingLabels.add(FloatingLabel("ACID", projectile.target.x, projectile.target.y - 0.3f, projectile.kind.accent, 0.45f))
                burst(projectile.x, projectile.y, projectile.kind.accent, 7, 0.65f)
            }
            TowerKind.GRAVEBOLT -> {
                var dmg = projectile.damage
                damageEnemy(projectile.target, dmg, projectile.kind.accent, 0.40f)
                val markT = if (tower.evolution == TowerEvolution.DEATH_KNELL) 4.2f else 2.8f
                val markD = dmg * (if (tower.evolution == TowerEvolution.DEATH_KNELL) 0.95f else 0.70f)
                // Always brand; Soul Brand guarantees detonate path (same field)
                projectile.target.graveMarkTimer = max(projectile.target.graveMarkTimer, markT)
                projectile.target.graveMarkDamage = max(projectile.target.graveMarkDamage, markD)
                if (tower.evolution == TowerEvolution.SOUL_BRAND) {
                    projectile.target.graveMarkDamage = max(projectile.target.graveMarkDamage, markD * 1.15f)
                }
                floatingLabels.add(FloatingLabel("BRAND", projectile.target.x, projectile.target.y - 0.3f, projectile.kind.accent, 0.45f))
                burst(projectile.x, projectile.y, projectile.kind.accent, 7, 0.65f)
            }
            TowerKind.AEGIS_LOOM -> {
                damageEnemy(projectile.target, projectile.damage, projectile.kind.accent, 0.30f)
                // Shield pulse: cleanse hex on nearby towers
                val cleanse = if (tower.evolution == TowerEvolution.BULWARK_WEAVE) 1.35f else 0.75f
                for (ally in towers) {
                    if (ally.disabledTimer <= 0f) continue
                    val dist = distanceSquared(ally.col + 0.5f, ally.row + 0.5f, tower.col + 0.5f, tower.row + 0.5f)
                    if (dist <= 6.25f) {
                        ally.disabledTimer = max(0f, ally.disabledTimer - cleanse)
                        burst(ally.col + 0.5f, ally.row + 0.5f, projectile.kind.accent, 4, 0.4f)
                    }
                }
                if (tower.evolution == TowerEvolution.WARD_PULSE) {
                    for (ally in towers) {
                        val dist = distanceSquared(ally.col + 0.5f, ally.row + 0.5f, tower.col + 0.5f, tower.row + 0.5f)
                        if (dist <= 6.25f) {
                            ally.focusBoostTimer = max(ally.focusBoostTimer, 1.6f)
                        }
                    }
                }
                floatingLabels.add(FloatingLabel("AEGIS", tower.col + 0.5f, tower.row + 0.2f, projectile.kind.accent, 0.5f))
                burst(projectile.x, projectile.y, projectile.kind.accent, 6, 0.55f)
            }
        }
    }

    private fun spawnImpact(x: Float, y: Float, kind: TowerKind) {
        impactEffects.add(ImpactFx(x, y, kind))
        val pitch = when (kind) {
            TowerKind.BOLT -> 1.08f + random.nextFloat() * 0.10f
            TowerKind.FROST -> 1.18f + random.nextFloat() * 0.10f
            TowerKind.CANNON -> 0.82f + random.nextFloat() * 0.08f
            TowerKind.EMBER -> 0.90f + random.nextFloat() * 0.10f
            TowerKind.BEACON -> 1.22f + random.nextFloat() * 0.10f
            TowerKind.THORN -> 1.15f + random.nextFloat() * 0.10f
            TowerKind.LANCE -> 0.95f + random.nextFloat() * 0.08f
            TowerKind.MIRE -> 0.75f + random.nextFloat() * 0.10f
            TowerKind.GALE -> 0.95f + random.nextFloat() * 0.10f
            TowerKind.SUNFORGE -> 0.88f + random.nextFloat() * 0.10f
            TowerKind.LODESTONE -> 0.70f + random.nextFloat() * 0.10f
            TowerKind.HOWL -> 0.92f + random.nextFloat() * 0.10f
            TowerKind.VITRIOL -> 0.78f + random.nextFloat() * 0.10f
            TowerKind.GRAVEBOLT -> 0.72f + random.nextFloat() * 0.10f
            TowerKind.AEGIS_LOOM -> 0.90f + random.nextFloat() * 0.08f
        }
        val volume = when (kind) {
            TowerKind.CANNON -> 0.38f
            TowerKind.EMBER -> 0.30f
            TowerKind.LANCE -> 0.32f
            TowerKind.MIRE -> 0.28f
            TowerKind.GALE -> 0.30f
            TowerKind.SUNFORGE -> 0.32f
            TowerKind.LODESTONE -> 0.30f
            TowerKind.HOWL -> 0.31f
            TowerKind.VITRIOL -> 0.29f
            TowerKind.GRAVEBOLT -> 0.33f
            TowerKind.AEGIS_LOOM -> 0.28f
            else -> 0.24f
        }
        audio.play("impact", volume, pitch)
    }

    private fun thermalShock(target: Enemy, sourceDamage: Float) {
        enemies.filter { it.targetable && abs(it.progress - target.progress) < 0.8f }.forEach { damageEnemy(it, sourceDamage * 0.45f, Color.rgb(255, 232, 180), 0.7f) }
        target.burnTimer = 0f
        burst(target.x, target.y, Color.rgb(255, 232, 180), 12, 0.9f)
    }

    private fun resetCrushersNear(x: Float, y: Float) {
        val crushers = traps.filter { it.kind == TrapKind.CRUSHER && distanceSquared(it.col + 0.5f, it.row + 0.5f, x, y) <= 6.25f }
        if (crushers.isEmpty()) return
        for (enemy in enemies) for (crusher in crushers) enemy.trapTriggerCounts.remove(crusher.id)
    }

    private fun ignite(enemy: Enemy, damagePerSecond: Float, duration: Float, sourceCol: Int = -1, sourceRow: Int = -1) {
        var dps = damagePerSecond
        if (sourceCol >= 0) dps *= cinderKilnBurnMul(sourceCol, sourceRow)
        enemy.burnTimer = max(enemy.burnTimer, duration)
        enemy.burnDamagePerSecond = max(enemy.burnDamagePerSecond, dps)
    }

    private fun applyBindingFrom(tower: Tower?, enemy: Enemy) {
        if (tower?.imbuement != Imbuement.BINDING) return
        enemy.rootTimer = max(enemy.rootTimer, 0.55f)
    }

    private fun damageEnemy(enemy: Enemy, amount: Float, effectColor: Int, armorPierce: Float = 0f, showLabel: Boolean = true) {
        if (!enemy.alive || enemy.dying) return
        var armor = enemy.kind.armor
        if (enemy.armoredTimer > 0f) armor += 0.24f
        if (enemy.armorShredTimer > 0f) armor = max(0f, armor - enemy.armorShred)
        if (challengeModifier == ChallengeModifier.ARMORED_HORDE) armor += 0.15f
        // F1 Thornback: extra armor while pulse is active
        if (enemy.kind == EnemyKind.THORNBACK && enemy.thornArmorTimer > 0f) armor += 0.22f
        armor = min(0.88f, armor) * (1f - armorPierce)
        var incoming = amount
        if (enemy.markTimer > 0f) incoming *= 1.22f
        var actual = max(0.5f, incoming * (1f - armor))
        // F5 Hollow Shell: shell buffer eats damage first
        if (enemy.shellBuffer > 0f) {
            val absorbed = min(enemy.shellBuffer, actual)
            enemy.shellBuffer -= absorbed
            actual -= absorbed
            if (enemy.shellBuffer <= 0f) {
                enemy.shellBuffer = 0f
                floatingLabels.add(FloatingLabel("SHELL BREAK", enemy.x, enemy.y - 0.25f, enemy.kind.color, 0.7f))
                burst(enemy.x, enemy.y, Color.rgb(230, 220, 200), 12, 0.75f)
            }
            if (actual <= 0.05f) {
                enemy.flashTimer = if (enemy.kind.boss) 0.18f else 0.14f
                return
            }
        }
        enemy.health -= actual
        enemy.flashTimer = if (enemy.kind.boss) 0.18f else 0.14f
        if (enemy.kind == EnemyKind.THORNBACK && enemy.health > 0f) enemy.thornArmorTimer = max(enemy.thornArmorTimer, 1.1f)
        // F6 Mirror Moth: spend reflect charge to shrug a hit
        if (enemy.kind == EnemyKind.MIRROR_MOTH && enemy.mirrorCharges > 0 && enemy.health > 0f) {
            enemy.mirrorCharges -= 1
            enemy.health = min(enemy.maxHealth, enemy.health + actual * 0.85f)
            floatingLabels.add(FloatingLabel("REFLECT", enemy.x, enemy.y - 0.2f, enemy.kind.color, 0.55f))
            burst(enemy.x, enemy.y, Color.rgb(200, 230, 255), 8, 0.55f)
        }
        // F5 Rust Tick: on hit, briefly rust (hex) nearest tower in range
        if (enemy.kind == EnemyKind.RUST_TICK && enemy.health > 0f && enemy.abilityTimer <= 0f) {
            val target = towers.filter { it.disabledTimer <= 0f }.minByOrNull {
                distanceSquared(it.col + 0.5f, it.row + 0.5f, enemy.x, enemy.y)
            }
            if (target != null && distanceSquared(target.col + 0.5f, target.row + 0.5f, enemy.x, enemy.y) <= 6.5f) {
                applyTowerHex(target, 1.1f * if (target.imbuement == Imbuement.CLARITY) 0.25f else 1f)
                floatingLabels.add(FloatingLabel("RUST", target.col + 0.5f, target.row + 0.35f, enemy.kind.color, 0.55f))
                enemy.abilityTimer = 3.2f
            }
        }
        if (showLabel && random.nextFloat() < 0.22f) floatingLabels.add(FloatingLabel(actual.toInt().toString(), enemy.x, enemy.y - 0.25f, effectColor, 0.7f))
        if (enemy.health > 0f) return
        beginEnemyDeath(enemy)
    }

    private fun beginEnemyDeath(enemy: Enemy) {
        if (enemy.dying || !enemy.alive) return
        enemy.health = 0f
        // F8c Gravebolt death detonate
        if (enemy.graveMarkTimer > 0.02f && enemy.graveMarkDamage > 0.5f) {
            val splash = enemy.graveMarkDamage
            val accent = TowerKind.GRAVEBOLT.accent
            burst(enemy.x, enemy.y, accent, 12, 0.9f)
            for (other in enemies) {
                if (!other.targetable || other === enemy) continue
                if (abs(other.progress - enemy.progress) < 1.35f) {
                    damageEnemy(other, splash * 0.85f, accent, 0.35f)
                }
            }
            floatingLabels.add(FloatingLabel("KNELL", enemy.x, enemy.y - 0.4f, accent, 0.55f))
            enemy.graveMarkTimer = 0f
            enemy.graveMarkDamage = 0f
        }
        enemy.deathTimer = if (enemy.kind.boss) 0.55f else if (enemy.kind.elite) 0.38f else 0.28f
        enemy.flashTimer = enemy.deathTimer
        enemy.burnTimer = 0f
        enemy.slowTimer = 0f
        enemy.markTimer = 0f
        enemy.wispTimer = 0f
        enemy.shellBuffer = 0f
        enemy.stunTimer = 0f
        if (!enemy.rewarded) {
            enemy.rewarded = true
            var bounty = enemy.bounty
            if ((enemy.kind.elite || enemy.kind.boss) && perkCount(ForgePerk.ELITE_BOUNTIES) > 0) bounty = (bounty * (1f + perkCount(ForgePerk.ELITE_BOUNTIES) * 0.50f)).toInt()
            gold = safeAdd(gold, bounty)
            score = safeAdd(score, bounty * 10)
            if (scrapMagnetKills > 0) {
                scrapMagnetKills -= 1
                salvageParts = safeAdd(salvageParts, 1)
                floatingLabels.add(FloatingLabel("+1 PART", enemy.x, enemy.y - 0.15f, Color.rgb(255, 200, 100), 0.5f))
            }
            // F6 Harvest imbuement: blocks trickle near imbued structures
            var harvest = 0
            for (tower in towers) {
                if (tower.imbuement != Imbuement.HARVEST) continue
                if (distanceSquared(tower.col + 0.5f, tower.row + 0.5f, enemy.x, enemy.y) <= 4.5f) harvest += 1
            }
            for (trap in traps) {
                if (trap.imbuement != Imbuement.HARVEST) continue
                if (distanceSquared(trap.col + 0.5f, trap.row + 0.5f, enemy.x, enemy.y) <= 4.5f) harvest += 1
            }
            if (harvest > 0) {
                gold = safeAdd(gold, harvest)
                if (random.nextFloat() < 0.35f) floatingLabels.add(FloatingLabel("+$harvest", enemy.x, enemy.y + 0.1f, Color.rgb(210, 180, 70), 0.45f))
            }

            // F7 Bounty Board + Fortune
            var bountyBonus = 0
            for (u in utilities) {
                if (u.kind != UtilityKind.BOUNTY_BOARD || u.disabledTimer > 0f) continue
                if (distanceSquared(u.col + 0.5f, u.row + 0.5f, enemy.x, enemy.y) <= 5.5f) {
                    bountyBonus += 1 + utilityPowerLevel(u) / 2
                }
            }
            for (tower in towers) {
                if (tower.imbuement == Imbuement.FORTUNE && distanceSquared(tower.col + 0.5f, tower.row + 0.5f, enemy.x, enemy.y) <= 4.5f) {
                    if (random.nextFloat() < 0.28f) bountyBonus += 2
                }
            }
            if (bountyBonus > 0) {
                gold = safeAdd(gold, bountyBonus)
                floatingLabels.add(FloatingLabel("+$bountyBonus", enemy.x, enemy.y + 0.2f, Color.rgb(255, 210, 90), 0.5f))
            }
            if (enemy.kind.elite || enemy.kind.boss) {
                val parts = if (enemy.kind.boss) 3 else 1
                salvageParts = safeAdd(salvageParts, parts)
                floatingLabels.add(FloatingLabel("+$parts PARTS", enemy.x, enemy.y - 0.25f, Color.rgb(202, 177, 137), life = 1.25f, pop = 1.35f))
            }
            if (enemy.kind.boss) growthEssence = safeAdd(growthEssence, 2)
            floatingLabels.add(FloatingLabel("+$bounty", enemy.x, enemy.y, Color.rgb(190, 244, 78), life = 1.2f, pop = 1.28f))
            if (lives < maxCore) {
                val leechNear = towers.any { it.imbuement == Imbuement.LEECH && distanceSquared(it.col + 0.5f, it.row + 0.5f, enemy.x, enemy.y) <= 9f } ||
                    traps.any { it.imbuement == Imbuement.LEECH && distanceSquared(it.col + 0.5f, it.row + 0.5f, enemy.x, enemy.y) <= 9f }
                if (leechNear && random.nextFloat() < (if (enemy.kind.elite || enemy.kind.boss) 0.55f else 0.18f)) {
                    lives = min(maxCore, lives + 1)
                    floatingLabels.add(FloatingLabel("+1 CORE", enemy.x, enemy.y - 0.4f, Color.rgb(255, 110, 130), life = 1.1f, pop = 1.35f))
                }
            }
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
            // F1 Mycelial: death heal nearby allies
            if (enemy.kind == EnemyKind.MYCELIAL) {
                for (ally in enemies) {
                    if (!ally.targetable || ally === enemy) continue
                    if (distanceSquared(ally.x, ally.y, enemy.x, enemy.y) <= 4.0f) {
                        ally.health = min(ally.maxHealth, ally.health + ally.maxHealth * 0.08f)
                    }
                }
                burst(enemy.x, enemy.y, enemy.kind.color, 14, 0.9f)
            }
            // F5 Hollow Shell: death armor gift to nearby allies
            if (enemy.kind == EnemyKind.HOLLOW_SHELL) {
                for (ally in enemies) {
                    if (!ally.targetable || ally === enemy) continue
                    if (distanceSquared(ally.x, ally.y, enemy.x, enemy.y) <= 3.6f) {
                        ally.armoredTimer = max(ally.armoredTimer, 2.2f)
                    }
                }
                burst(enemy.x, enemy.y, enemy.kind.color, 16, 0.95f)
            }
            // F5 Drift Seed: death burst marks nearby foes? No - seed scatter slows tower fire? 
            // death: short disable nearest trap or nothing - seed scatter as minor heal to core? skip
            // F1 Carrion Hulk: death hex nearest tower
            if (enemy.kind == EnemyKind.CARRION_HULK) {
                val target = towers.filter { it.disabledTimer <= 0f }.minByOrNull { distanceSquared(it.col + 0.5f, it.row + 0.5f, enemy.x, enemy.y) }
                if (target != null && distanceSquared(target.col + 0.5f, target.row + 0.5f, enemy.x, enemy.y) <= 9f) {
                    applyTowerHex(target, (if (hasHarmonyNear(target)) 0.9f else 2.0f) * if (target.imbuement == Imbuement.CLARITY) 0.25f else 1f)
                    floatingLabels.add(FloatingLabel("HEXED", target.col + 0.5f, target.row + 0.35f, enemy.kind.color))
                }
            }
        }
    }

    private fun removeDefeatedEnemies() {
        enemies.removeAll { !it.alive }
    }

    private fun updateEffects(delta: Float) {
        for (trap in traps) {
            trap.pulse = max(0f, trap.pulse - delta * 3.2f)
            trap.jamTimer = max(0f, trap.jamTimer - delta)
        }
        for (particle in particles) {
            particle.life -= delta
            particle.x += particle.velocityX * delta
            particle.y += particle.velocityY * delta
            particle.velocityY += 0.45f * delta
            particle.velocityX *= 0.985f
        }
        particles.removeAll { it.life <= 0f }
        for (fx in impactEffects) fx.age += delta
        impactEffects.removeAll { !it.alive }
        for (label in floatingLabels) {
            label.life -= delta
            val boost = if (label.pop > 1.05f) 1.25f else 1f
            label.y -= delta * 0.38f * boost
        }
        floatingLabels.removeAll { it.life <= 0f }
    }

    private fun completeWave() {
        var reward = min(250_000L, 45L + waveNumber.toLong() * 13L).toInt()
        reward = (reward * (1f + perkCount(ForgePerk.COMPOUND_BLOCKS) * 0.20f)).toInt()
        if (perkCount(ForgePerk.LONG_ROAD_DIVIDEND) > 0) reward = safeAdd(reward, max(0, pathCells.size - 12) * 3 * perkCount(ForgePerk.LONG_ROAD_DIVIDEND))
        val utilityIncome = processUtilityWaveClear()
        gold = safeAdd(gold, safeAdd(reward, utilityIncome))
        score = safeAdd(score, reward * 5 + utilityIncome * 3)
        if (surveyLensWaves > 0) surveyLensWaves -= 1
        if (waveNumber % 5 == 0 && perkCount(ForgePerk.CORE_REGENERATION) > 0) lives = min(maxCore, lives + perkCount(ForgePerk.CORE_REGENERATION))
        if (waveNumber % 10 == 0) {
            val tier = waveNumber / 10
            lives = min(maxCore, lives + 1)
            forgeCharges = safeAdd(forgeCharges, 6 + tier * 2 + perkCount(ForgePerk.FORGE_MASTERY) * 2 + perkCount(ForgePerk.BOSS_HARVEST) * 3)
            evolutionCores = safeAdd(evolutionCores, 1 + perkCount(ForgePerk.BOSS_HARVEST))
            spreadBossCorruption(tier)
            activateBossCycleUtilities()
            setBanner("BOSS BROKEN  +${safeAdd(reward, utilityIncome)} BLOCKS  +${6 + tier * 2} FORGE", 3.0f)
        } else {
            setBanner("WAVE $waveNumber CLEARED  +${safeAdd(reward, utilityIncome)} BLOCKS", 2.4f)
        if (routeOilWaves > 0) routeOilWaves -= 1
        }
        selectedTower = null
        selectedTrap = null
        selectedUtility = null
        selectedCorruption = null
        updateRecords()
        audio.play("build", 0.45f, 1.14f)
        if (waveNumber % 5 == 0) {
            generatePerkChoices()
            phase = GamePhase.PERK_DRAFT
            saveRun()
        } else {
            phase = GamePhase.BUILD
            saveRun()
        }
    }

    private fun processUtilityWaveClear(): Int {
        var income = 0
        for (utility in utilities) {
            if (utility.disabledTimer > 0f) continue
            when (utility.kind) {
                UtilityKind.BLOCK_GENERATOR -> {
                    val base = when (utility.level) { 1 -> 18; 2 -> 30; else -> 45 }
                    val adjacentRoute = pathCells.count { abs(it.col - utility.col) + abs(it.row - utility.row) == 1 }
                    val outputScale = (if (utility.imbuement == Imbuement.MIGHT) 1.15f else 1f) * (if (utility.imbuement == Imbuement.TEMPO) 1.10f else 1f)
                    var produced = ((base + min(3, adjacentRoute) * 3) * outputScale).toInt()
                    utility.activationCount += 1
                    if (utility.imbuement == Imbuement.ECHOES && utility.activationCount % 5 == 0) produced += produced / 2
                    income = safeAdd(income, produced)
                    floatingLabels.add(FloatingLabel("+$produced", utility.col + 0.5f, utility.row + 0.25f, utility.kind.accent, life = 1.15f, pop = 1.32f))
                }
                UtilityKind.FORGE_WORKSHOP -> {
                    utility.productionProgress += 1
                    if (utility.productionProgress >= utility.cycleWaves(5)) {
                        utility.productionProgress = 0
                        utility.activationCount += 1
                        val current = supplyCount(CraftedItem.CORE_PATCH)
                        var produced = if (utility.imbuement == Imbuement.MIGHT) 2 else 1
                        if (utility.imbuement == Imbuement.ECHOES && utility.activationCount % 5 == 0) produced += 1
                        if (current < CraftedItem.CORE_PATCH.maxStack) supplies[CraftedItem.CORE_PATCH] = min(CraftedItem.CORE_PATCH.maxStack, current + produced)
                    }
                }
                UtilityKind.ESSENCE_STILL -> {
                    utility.activationCount += 1
                    var essence = when (utilityPowerLevel(utility)) { 1 -> 1; 2 -> 1; 3 -> 2; else -> 2 }
                    if (utility.imbuement == Imbuement.MIGHT) essence += 1
                    if (utility.imbuement == Imbuement.ECHOES && utility.activationCount % 5 == 0) essence += 1
                    growthEssence = safeAdd(growthEssence, essence)
                    floatingLabels.add(FloatingLabel("+$essence E", utility.col + 0.5f, utility.row + 0.25f, utility.kind.accent, life = 1.15f, pop = 1.32f))
                }
                UtilityKind.GROWTH_NURSERY -> {
                    utility.activationCount += 1
                    var essenceN = 1 + utilityPowerLevel(utility) / 2
                    if (utility.imbuement == Imbuement.MIGHT) essenceN += 1
                    growthEssence = safeAdd(growthEssence, essenceN)
                    floatingLabels.add(FloatingLabel("+$essenceN E", utility.col + 0.5f, utility.row + 0.25f, utility.kind.accent, life = 1.1f, pop = 1.25f))
                }
                UtilityKind.WARD_BEACON -> {
                    utility.activationCount += 1
                    val radius = 2.2f + utilityPowerLevel(utility) * 0.25f
                    for (tower in towers) {
                        if (distanceSquared(tower.col.toFloat(), tower.row.toFloat(), utility.col.toFloat(), utility.row.toFloat()) <= radius * radius) {
                            val key = towerKey(tower.col, tower.row)
                            coolingImmunity[key] = max(coolingImmunity[key] ?: 0f, 2.5f)
                        }
                    }
                }
                else -> Unit
            }
        }
        return income
    }

    private fun activateBossCycleUtilities() {
        for (utility in utilities.filter { it.kind == UtilityKind.PURIFIER_TOTEM && it.level >= 3 && it.disabledTimer <= 0f }) {
            val radiusSquared = utility.effectRadius() * utility.effectRadius()
            utility.activationCount += 1
            val cleanseCount = if (utility.imbuement == Imbuement.ECHOES && utility.activationCount % 5 == 0) 2 else 1
            repeat(cleanseCount) {
                val target = corruptions.filter { distanceSquared(it.cell.col + 0.5f, it.cell.row + 0.5f, utility.col + 0.5f, utility.row + 0.5f) <= radiusSquared }.minByOrNull { distanceSquared(it.cell.col + 0.5f, it.cell.row + 0.5f, utility.col + 0.5f, utility.row + 0.5f) }
                if (target != null) {
                    corruptions.remove(target)
                    growthEssence = safeAdd(growthEssence, 1)
                    floatingLabels.add(FloatingLabel("PURIFIED", target.cell.col + 0.5f, target.cell.row + 0.3f, utility.kind.accent))
                }
            }
        }
    }

    private fun generatePerkChoices() {
        perkChoices.clear()
        val pool = ForgePerk.values().toMutableList()
        val choiceRandom = Random(runSeed xor (waveNumber.toLong() * 0x5DEECE66DL))
        while (perkChoices.size < 3 && pool.isNotEmpty()) {
            val index = choiceRandom.nextInt(pool.size)
            perkChoices.add(pool.removeAt(index))
        }
    }

    private fun choosePerk(perk: ForgePerk) {
        perks[perk] = perkCount(perk) + 1
        when (perk) {
            ForgePerk.EMERGENCY_SHIELD -> {
                maxCore += 2
                lives = min(maxCore, lives + 2)
            }
            ForgePerk.PHASE_BARRIER -> phaseBarrierReady = true
            ForgePerk.FORGE_MASTERY -> forgeCharges = safeAdd(forgeCharges, 3)
            else -> Unit
        }
        phase = GamePhase.BUILD
        setBanner("FORGE PERK  ${perk.title.uppercase()}", 2.5f)
        saveRun()
        audio.play("build", 0.60f, 1.22f)
    }

    private fun spreadBossCorruption(tier: Int) {
        var count = min(6, 2 + tier)
        if (challengeModifier == ChallengeModifier.DOUBLE_CORRUPTION) count *= 2
        count = max(1, count - perkCount(ForgePerk.CORRUPTION_WARD))
        val corruptionRandom = Random(runSeed xor (waveNumber.toLong() * 7919L))
        val kinds = CorruptionKind.values()
        repeat(count) {
            val kind = kinds[corruptionRandom.nextInt(kinds.size)]
            val candidates = if (kind.pathPreferred) {
                pathCells.drop(1).dropLast(1).filter { cell -> findTrap(cell.col, cell.row) == null && corruptions.none { it.cell == cell } }
            } else {
                val cells = ArrayList<GridCell>()
                for (row in 0 until ROWS) for (col in 0 until COLS) {
                    val cell = GridCell(col, row)
                    if (!pathCells.contains(cell) && findTower(col, row) == null && findTrap(col, row) == null && findUtility(col, row) == null && corruptions.none { it.cell == cell }) cells.add(cell)
                }
                cells
            }
            if (candidates.isNotEmpty()) {
                if (corruptions.size >= 18) corruptions.removeAt(0)
                corruptions.add(CorruptedCell(nextCorruptionId++, candidates[corruptionRandom.nextInt(candidates.size)], kind))
            }
        }
    }

    private fun finishRun(victory: Boolean) {
        phase = if (victory) GamePhase.VICTORY else GamePhase.GAME_OVER
        selectedTower = null
        selectedTrap = null
        selectedUtility = null
        selectedCorruption = null
        updateRecords()
        clearSavedRun()
    }

    private fun updateRecords() {
        val editor = preferences.edit()
        if (gameMode == GameMode.ENDLESS) {
            if (score > bestScore) { bestScore = score; editor.putInt("best_score", bestScore) }
            if (waveNumber > bestWave) { bestWave = waveNumber; editor.putInt("best_wave", bestWave) }
        } else if (gameMode == GameMode.DAILY) {
            if (score > bestDailyScore) { bestDailyScore = score; editor.putInt("best_daily_score", bestDailyScore) }
            if (waveNumber > bestDailyWave) { bestDailyWave = waveNumber; editor.putInt("best_daily_wave", bestDailyWave) }
        } else {
            if (score > bestCustomScore) { bestCustomScore = score; editor.putInt("best_custom_score", bestCustomScore) }
            if (waveNumber > bestCustomWave) { bestCustomWave = waveNumber; editor.putInt("best_custom_wave", bestCustomWave) }
        }
        editor.apply()
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
        selectedCorruption = null
        phase = GamePhase.WAVE
        for (tower in towers) if (tower.imbuement == Imbuement.SURGE) tower.surgeCharges = 3
        for (trap in traps) if (trap.imbuement == Imbuement.SURGE) trap.surgeCharges = 3
        val message = when {
            waveNumber % 10 == 0 -> {
                val tier = waveNumber / 10
                val bossName = when {
                    waveNumber % 30 == 0 -> "MUTATED OVERGROWTH"
                    tier % 3 == 1 -> "IRON MONARCH"
                    tier % 3 == 2 -> "SPORE SOVEREIGN"
                    else -> "MUTATED OVERGROWTH"
                }
                "$bossName  TIER $tier"
            }
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
        val regularCount = min(36, 6 + wave / 2)
        val regulars = arrayOf(
            EnemyKind.MOSSER, EnemyKind.RUNNER, EnemyKind.BRUTE, EnemyKind.SHELLBACK, EnemyKind.SPLITLING,
            EnemyKind.SAPPER, EnemyKind.MYCELIAL, EnemyKind.NEEDLEFLY, EnemyKind.GLOOMKIN, EnemyKind.CARRION_HULK
        )
        val seedOffset = ((runSeed xor wave.toLong()) and Long.MAX_VALUE).rem(regulars.size.toLong()).toInt()
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
        val count = if (wave % 8 == 1) min(42, regularCount + 8) else regularCount
        repeat(count) { index ->
            val kind = when (wave % 8) {
                1 -> when (index % 5) {
                    0 -> EnemyKind.SPLITLING
                    1 -> EnemyKind.MYCELIAL
                    else -> EnemyKind.MOSSER
                }
                2 -> when (index % 5) {
                    0 -> EnemyKind.NEEDLEFLY
                    1 -> EnemyKind.MOSSER
                    else -> EnemyKind.RUNNER
                }
                3 -> when (index % 5) {
                    0 -> EnemyKind.BRUTE
                    1 -> EnemyKind.CARRION_HULK
                    2 -> EnemyKind.HOLLOW_SHELL
                    else -> EnemyKind.SHELLBACK
                }
                4 -> when (index % 6) {
                    0 -> EnemyKind.MYCELIAL
                    1 -> EnemyKind.SHELLBACK
                    2 -> EnemyKind.RUST_TICK
                    else -> EnemyKind.MOSSER
                }
                5 -> when (index % 7) {
                    0 -> EnemyKind.SAPPER
                    1 -> EnemyKind.GLOOMKIN
                    2 -> EnemyKind.WISP_DRIFTER
                    3 -> EnemyKind.RUST_TICK
                    else -> regulars[(index + wave + seedOffset) % regulars.size]
                }
                6 -> when (index % 5) {
                    0 -> EnemyKind.BRUTE
                    1 -> EnemyKind.CARRION_HULK
                    2 -> EnemyKind.HOLLOW_SHELL
                    else -> EnemyKind.SHELLBACK
                }
                7 -> when (index % 6) {
                    0 -> EnemyKind.NEEDLEFLY
                    1 -> EnemyKind.SPLITLING
                    2 -> EnemyKind.DRIFT_SEED
                    3 -> EnemyKind.BRIAR_MITE
                    else -> EnemyKind.RUNNER
                }
                else -> regulars[(index * 3 + wave + seedOffset) % regulars.size]
            }
            val speedMul = when (kind) {
                EnemyKind.RUNNER, EnemyKind.NEEDLEFLY, EnemyKind.BRIAR_MITE, EnemyKind.DRIFT_SEED -> 1.03f
                else -> 1f
            }
            waveQueue.add(SpawnSpec(kind, healthScale, speedScale * speedMul, rewardScale))
        }
        if (wave % 8 == 5 && wave % 5 != 0) waveQueue.add(min(waveQueue.size, waveQueue.size * 2 / 3), SpawnSpec(EnemyKind.HEX_WEAVER, healthScale * 0.34f, speedScale, rewardScale * 0.55f))
        if (wave % 5 == 0) {
            val elites = arrayOf(
                EnemyKind.IRONHIDE, EnemyKind.BLINK_STALKER, EnemyKind.ROOTCALLER, EnemyKind.HEX_WEAVER,
                EnemyKind.SIEGE_COLOSSUS, EnemyKind.THORNBACK, EnemyKind.GRAVE_MENDER, EnemyKind.PYRE_WIGHT, EnemyKind.VEIN_LURKER, EnemyKind.MIRROR_MOTH
            )
            val eliteKind = elites[((wave / 5) - 1 + seedOffset) % elites.size]
            val insertAt = min(waveQueue.size, waveQueue.size * 2 / 3)
            waveQueue.add(insertAt, SpawnSpec(eliteKind, healthScale * 0.72f, speedScale, rewardScale * 1.1f))
            if (wave >= 30) waveQueue.add(min(waveQueue.size, insertAt + 5), SpawnSpec(elites[((wave / 5) + 1 + seedOffset) % elites.size], healthScale * 0.58f, speedScale, rewardScale))
        }
        if (wave % 10 == 0) {
            val tier = wave / 10
            // F2: rotate named bosses; Overgrowth remains the signature every 30
            val bossKind = when {
                wave % 30 == 0 -> EnemyKind.OVERGROWTH
                tier % 5 == 1 -> EnemyKind.IRON_MONARCH
                tier % 5 == 2 -> EnemyKind.SPORE_SOVEREIGN
                tier % 5 == 3 -> EnemyKind.TIDAL_ROOT
                tier % 5 == 4 -> EnemyKind.ASHEN_CHOIR
                else -> EnemyKind.OVERGROWTH
            }
            waveQueue.add(SpawnSpec(bossKind, healthScale * (0.78f + min(1.2f, tier * 0.05f)), min(1.28f, speedScale), rewardScale * 1.3f, tier))
        }
        val broodCount = corruptions.count { it.kind == CorruptionKind.BROOD_NEST }
        repeat(min(18, broodCount)) { waveQueue.add(min(waveQueue.size, 2 + it), SpawnSpec(EnemyKind.SPLITLING, healthScale * 0.28f, speedScale * 1.12f, rewardScale * 0.25f, splitDepth = 1)) }
    }

    private fun surveyAvailable(): Boolean = surveyLensWaves > 0 || utilities.any { it.kind == UtilityKind.SURVEYOR_STATION }

    private fun surveyPreviewText(wave: Int): String {
        val surveyor = utilities.filter { it.kind == UtilityKind.SURVEYOR_STATION }.maxByOrNull { utilityPowerLevel(it) }
        val level = surveyor?.let { utilityPowerLevel(it) } ?: if (surveyLensWaves > 0) 2 else 0
        val theme = when (wave % 8) { 1 -> "SWARM"; 2 -> "RUSH"; 3 -> "ARMORED"; 4 -> "REGEN"; 5 -> "SABOTAGE"; 6 -> "SIEGE"; 7 -> "SPLIT"; else -> "MIXED" }
        val count = min(34, 6 + wave / 2) + if (wave % 8 == 1) 8 else 0
        val threat = when {
            wave % 10 == 0 -> {
                val tier = wave / 10
                when {
                    wave % 30 == 0 -> "OVERGROWTH T$tier"
                    tier % 3 == 1 -> "IRON MONARCH T$tier"
                    tier % 3 == 2 -> "SPORE SOVEREIGN T$tier"
                    else -> "OVERGROWTH T$tier"
                }
            }
            wave % 5 == 0 -> "ELITE SIGNAL"
            else -> "NO ELITE"
        }
        return when (level) { 1 -> "SURVEY • WAVE $wave $theme"; 2 -> "SURVEY • WAVE $wave $theme • ~$count HOSTILES"; else -> "SURVEY • WAVE $wave $theme • ~$count • $threat" }
    }

    private fun waveHealthScale(wave: Int): Float {
        val exponentialWave = min(80, max(0, wave - 1))
        val tail = max(0, wave - 81)
        return min(1_000_000_000f, 1.085.pow(exponentialWave.toDouble()).toFloat() * (1f + min(100_000, tail) * 0.055f))
    }

    private fun perkCount(perk: ForgePerk): Int = perks[perk] ?: 0

    private fun distanceSquared(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x1 - x2
        val dy = y1 - y2
        return dx * dx + dy * dy
    }

    private fun isNearTowerKind(col: Int, row: Int, kind: TowerKind, range: Float = 2.5f, exclude: Tower? = null): Boolean {
        return towers.any { it !== exclude && it.kind == kind && distanceSquared(it.col.toFloat(), it.row.toFloat(), col.toFloat(), row.toFloat()) <= range * range }
    }

    private fun isNearTrapKind(col: Int, row: Int, kind: TrapKind, range: Float = 2.5f): Boolean {
        return traps.any { it.kind == kind && distanceSquared(it.col.toFloat(), it.row.toFloat(), col.toFloat(), row.toFloat()) <= range * range }
    }

    private fun hasCinderReactorNear(col: Int, row: Int): Boolean {
        return towers.any { it.evolution == TowerEvolution.CINDER_REACTOR && distanceSquared(it.col.toFloat(), it.row.toFloat(), col.toFloat(), row.toFloat()) <= 8f }
    }

    private fun hasHarmonyNear(tower: Tower): Boolean {
        return towers.any { it !== tower && it.evolution == TowerEvolution.HARMONY_NEXUS && distanceSquared(it.col.toFloat(), it.row.toFloat(), tower.col.toFloat(), tower.row.toFloat()) <= 10f }
    }

    private fun enemyOnCorner(enemy: Enemy): Boolean {
        val index = enemy.progress.toInt()
        if (index <= 0 || index >= pathCells.size - 1) return false
        val before = pathCells[index - 1]
        val current = pathCells[index]
        val after = pathCells[index + 1]
        return (before.col != current.col && after.row != current.row) || (before.row != current.row && after.col != current.col)
    }

    private fun currentPathLimit(): Int {
        if (challengeModifier == ChallengeModifier.SHORT_ROUTE) return 16
        return min(COLS * ROWS, MAX_PATH_LENGTH + perkCount(ForgePerk.DEEP_ROUTE) * 8)
    }

    private fun utilityPowerLevel(utility: Utility): Int = min(4, utility.level + if (utility.imbuement == Imbuement.MIGHT) 1 else 0)

    private fun recyclingMultiplier(): Float {
        val salvageBonus = utilities.filter { it.kind == UtilityKind.SALVAGE_YARD }.map { utilityPowerLevel(it) * 0.05f }.maxOrNull() ?: 0f
        return min(0.95f, 0.60f + perkCount(ForgePerk.BETTER_RECYCLING) * 0.15f + salvageBonus)
    }

    private fun cacheCapacity(): Int {
        val depot = utilities.filter { it.kind == UtilityKind.CACHE_DEPOT }.maxByOrNull { it.level }
        return min(20, 4 + if (depot == null) 0 else when (utilityPowerLevel(depot)) { 1 -> 3; 2 -> 5; 3 -> 7; else -> 8 })
    }

    private fun cacheStorageDiscount(): Float {
        val depot = utilities.filter { it.kind == UtilityKind.CACHE_DEPOT }.maxByOrNull { it.level }
        if (depot == null) return 0f
        val conservation = if (depot.imbuement == Imbuement.CONSERVATION) 0.10f else 0f
        return min(0.55f, utilityPowerLevel(depot) * 0.10f + conservation)
    }

    private fun trapStorageCost(trap: SpikeTrap): Int {
        var cost = max(20, (trap.kind.cost * 0.30f).toInt() + (trap.level - 1) * 12 + trap.overcharge * 8)
        cost = (cost * (1f - cacheStorageDiscount())).toInt()
        if (trap.imbuement == Imbuement.CONSERVATION) cost = (cost * 0.85f).toInt()
        return max(1, cost)
    }

    private fun utilityCapacity(): Int = 4

    private fun workshopLevel(): Int = utilities.filter { it.kind == UtilityKind.FORGE_WORKSHOP }.map { it.level }.maxOrNull() ?: 0

    private fun craftedBlockCost(item: CraftedItem): Int {
        val conservation = utilities.any { it.kind == UtilityKind.FORGE_WORKSHOP && it.imbuement == Imbuement.CONSERVATION }
        return if (conservation) max(1, (item.blockCost * 0.85f).toInt()) else item.blockCost
    }

    private fun supplyCount(item: CraftedItem): Int = supplies[item] ?: 0

    private fun towerKey(col: Int, row: Int) = col * 100 + row

    private fun hasCoolingImmunity(col: Int, row: Int) = (coolingImmunity[towerKey(col, row)] ?: 0f) > 0.02f

    private fun applyTowerHex(tower: Tower, duration: Float) {
        if (hasCoolingImmunity(tower.col, tower.row)) {
            floatingLabels.add(FloatingLabel("COOLED", tower.col + 0.5f, tower.row + 0.2f, Color.rgb(93, 220, 255), pop = 1.3f))
            return
        }
        var d = duration
        if (tower.imbuement == Imbuement.WARD) d *= 0.55f
        if (tower.imbuement == Imbuement.BULWARK) d *= 0.65f
        if (hasWardBeaconNear(tower.col, tower.row)) d *= 0.70f
        d *= aegisHexMul(tower.col, tower.row)
        tower.disabledTimer = max(tower.disabledTimer, d)
    }

    private fun hasWardBeaconNear(col: Int, row: Int): Boolean {
        return utilities.any {
            it.kind == UtilityKind.WARD_BEACON && it.disabledTimer <= 0f &&
                distanceSquared(it.col.toFloat(), it.row.toFloat(), col.toFloat(), row.toFloat()) <= 6.25f
        }
    }

    private fun battleBannerDamageBonus(col: Int, row: Int): Float {
        val banner = utilities.filter {
            it.kind == UtilityKind.BATTLE_BANNER && it.disabledTimer <= 0f &&
                distanceSquared(it.col.toFloat(), it.row.toFloat(), col.toFloat(), row.toFloat()) <= 6.25f
        }.maxByOrNull { utilityPowerLevel(it) } ?: return 1f
        return 1f + 0.08f * utilityPowerLevel(banner) + if (banner.imbuement == Imbuement.MIGHT) 0.05f else 0f
    }

    private fun trapLatticeBonus(col: Int, row: Int): Float {
        val lattice = utilities.filter {
            it.kind == UtilityKind.TRAP_LATTICE && it.disabledTimer <= 0f &&
                distanceSquared(it.col.toFloat(), it.row.toFloat(), col.toFloat(), row.toFloat()) <= 6.25f
        }.maxByOrNull { utilityPowerLevel(it) } ?: return 1f
        return 1f + 0.10f * utilityPowerLevel(lattice) + if (lattice.imbuement == Imbuement.MIGHT) 0.06f else 0f
    }

    private fun sparkRelayIntervalMul(col: Int, row: Int): Float {
        val relay = utilities.filter {
            it.kind == UtilityKind.SPARK_RELAY && it.disabledTimer <= 0f &&
                distanceSquared(it.col.toFloat(), it.row.toFloat(), col.toFloat(), row.toFloat()) <= 6.25f
        }.maxByOrNull { utilityPowerLevel(it) } ?: return 1f
        return max(0.72f, 1f - 0.05f * utilityPowerLevel(relay) - if (relay.imbuement == Imbuement.TEMPO) 0.04f else 0f)
    }

    private fun pathWardenSlow(x: Float, y: Float): Float {
        val warden = utilities.filter {
            it.kind == UtilityKind.PATH_WARDEN && it.disabledTimer <= 0f &&
                distanceSquared(it.col + 0.5f, it.row + 0.5f, x, y) <= 5.5f
        }.maxByOrNull { utilityPowerLevel(it) } ?: return 1f
        return max(0.70f, 1f - 0.06f * utilityPowerLevel(warden))
    }

    private fun cinderKilnBurnMul(col: Int, row: Int): Float {
        val kiln = utilities.filter {
            it.kind == UtilityKind.CINDER_KILN && it.disabledTimer <= 0f &&
                distanceSquared(it.col.toFloat(), it.row.toFloat(), col.toFloat(), row.toFloat()) <= 6.25f
        }.maxByOrNull { utilityPowerLevel(it) } ?: return 1f
        return 1f + 0.10f * utilityPowerLevel(kiln)
    }

    private fun aegisHexMul(col: Int, row: Int): Float {
        val pylon = utilities.filter {
            it.kind == UtilityKind.AEGIS_PYLON && it.disabledTimer <= 0f &&
                distanceSquared(it.col.toFloat(), it.row.toFloat(), col.toFloat(), row.toFloat()) <= 6.25f
        }.maxByOrNull { utilityPowerLevel(it) } ?: return 1f
        return max(0.55f, 1f - 0.08f * utilityPowerLevel(pylon))
    }

    private fun consumeSupply(item: CraftedItem): Boolean {
        val count = supplyCount(item)
        if (count <= 0) return false
        if (count == 1) supplies.remove(item) else supplies[item] = count - 1
        return true
    }

    private fun safeAdd(value: Int, addition: Int): Int {
        return min(2_000_000_000L, value.toLong() + addition.toLong()).toInt()
    }

    private fun saveRun() {
        if (!pathComplete || phase == GamePhase.GAME_OVER || phase == GamePhase.VICTORY) return
        val pathData = pathCells.joinToString(";") { "${it.col},${it.row}" }
        val towerData = towers.joinToString(";") { "${it.col},${it.row},${it.kind.name},${it.level},${it.overcharge},${it.evolution?.name ?: "NONE"},${it.imbuement?.name ?: "NONE"}" }
        val trapData = traps.joinToString(";") { "${it.id},${it.col},${it.row},${it.kind.name},${it.level},${it.overcharge},${it.imbuement?.name ?: "NONE"}" }
        val utilityData = utilities.joinToString(";") { "${it.col},${it.row},${it.kind.name},${it.level},${it.imbuement?.name ?: "NONE"},${it.productionProgress},${it.activationCount}" }
        val corruptionData = corruptions.joinToString(";") { "${it.id},${it.cell.col},${it.cell.row},${it.kind.name}" }
        val perkData = perks.entries.joinToString(";") { "${it.key.name},${it.value}" }
        val pendingPerkData = if (phase == GamePhase.PERK_DRAFT) perkChoices.joinToString(",") { it.name } else ""
        val inventoryData = storedTraps.joinToString(";") { "${it.kind.name},${it.level},${it.overcharge},${it.imbuement?.name ?: "NONE"}" }
        val supplyData = supplies.entries.filter { it.value > 0 }.joinToString(";") { "${it.key.name},${it.value}" }
        preferences.edit()
            .putInt("run_save_version", 3)
            .putBoolean("has_saved_run", true)
            .putString("run_path", pathData)
            .putString("run_towers", towerData)
            .putString("run_traps", trapData)
            .putString("run_utilities", utilityData)
            .putString("run_corruptions", corruptionData)
            .putString("run_perks", perkData)
            .putString("run_pending_perks", pendingPerkData)
            .putString("run_inventory", inventoryData)
            .putString("run_supplies", supplyData)
            .putInt("run_gold", gold)
            .putInt("run_lives", lives)
            .putInt("run_max_core", maxCore)
            .putInt("run_score", score)
            .putInt("run_wave", waveNumber)
            .putInt("run_forge_charges", forgeCharges)
            .putInt("run_evolution_cores", evolutionCores)
            .putInt("run_salvage_parts", salvageParts)
            .putInt("run_growth_essence", growthEssence)
            .putInt("run_survey_lens_waves", surveyLensWaves)
            .putBoolean("run_phase_barrier", phaseBarrierReady)
            .putBoolean("run_splinter_brace", splinterBraceReady)
            .putString("run_mode", gameMode.name)
            .putString("run_modifier", challengeModifier.name)
            .putLong("run_seed", runSeed)
            .apply()
        savedRunAvailable = true
    }

    private fun loadSavedRun() {
        if (!prefBoolean("has_saved_run", false)) return
        try {
            val loadedPath = preferences.getString("run_path", "").orEmpty().split(';').filter { it.isNotBlank() }.map {
                val values = it.split(',')
                GridCell(values[0].toInt(), values[1].toInt())
            }
            if (!isValidSavedPath(loadedPath)) throw IllegalStateException("Invalid path")
            pathCells.clear()
            pathCells.addAll(loadedPath)
            towers.clear()
            preferences.getString("run_towers", "").orEmpty().split(';').filter { it.isNotBlank() }.take(COLS * ROWS).forEach {
                val values = it.split(',')
                val col = values[0].toInt()
                val row = values[1].toInt()
                if (col !in 0 until COLS || row !in 0 until ROWS) throw IllegalStateException("Invalid tower cell")
                val tower = Tower(col, row, TowerKind.valueOf(values[2]))
                tower.level = values[3].toInt().coerceIn(1, 3)
                tower.overcharge = values[4].toInt().coerceIn(0, 999)
                tower.evolution = if (values.size >= 6 && values[5] != "NONE") TowerEvolution.valueOf(values[5]) else null
                if (tower.evolution?.kind != null && tower.evolution?.kind != tower.kind) tower.evolution = null
                tower.imbuement = if (values.size >= 7 && values[6] != "NONE") Imbuement.valueOf(values[6]) else null
                towers.add(tower)
            }
            traps.clear()
            preferences.getString("run_traps", "").orEmpty().split(';').filter { it.isNotBlank() }.take(COLS * ROWS).forEach {
                val values = it.split(',')
                val col = values[1].toInt()
                val row = values[2].toInt()
                if (col !in 0 until COLS || row !in 0 until ROWS) throw IllegalStateException("Invalid trap cell")
                val trap = SpikeTrap(values[0].toInt(), col, row, TrapKind.valueOf(values[3]))
                trap.level = values[4].toInt().coerceIn(1, 3)
                trap.overcharge = values[5].toInt().coerceIn(0, 999)
                trap.imbuement = if (values.size >= 7 && values[6] != "NONE") Imbuement.valueOf(values[6]) else null
                traps.add(trap)
            }
            utilities.clear()
            preferences.getString("run_utilities", "").orEmpty().split(';').filter { it.isNotBlank() }.take(utilityCapacity()).forEach {
                val values = it.split(',')
                val col = values[0].toInt()
                val row = values[1].toInt()
                if (col !in 0 until COLS || row !in 0 until ROWS) throw IllegalStateException("Invalid utility cell")
                val utility = Utility(col, row, UtilityKind.valueOf(values[2]))
                utility.level = values[3].toInt().coerceIn(1, 3)
                utility.imbuement = if (values.size >= 5 && values[4] != "NONE") Imbuement.valueOf(values[4]) else null
                utility.productionProgress = if (values.size >= 6) values[5].toInt().coerceIn(0, 20) else 0
                utility.activationCount = if (values.size >= 7) values[6].toInt().coerceIn(0, 2_000_000_000) else 0
                utilities.add(utility)
            }
            corruptions.clear()
            preferences.getString("run_corruptions", "").orEmpty().split(';').filter { it.isNotBlank() }.take(18).forEach {
                val values = it.split(',')
                val col = values[1].toInt()
                val row = values[2].toInt()
                if (col !in 0 until COLS || row !in 0 until ROWS) throw IllegalStateException("Invalid corruption cell")
                corruptions.add(CorruptedCell(values[0].toInt(), GridCell(col, row), CorruptionKind.valueOf(values[3])))
            }
            perks.clear()
            preferences.getString("run_perks", "").orEmpty().split(';').filter { it.isNotBlank() }.forEach {
                val values = it.split(',')
                perks[ForgePerk.valueOf(values[0])] = values[1].toInt().coerceIn(1, 99)
            }
            perkChoices.clear()
            preferences.getString("run_pending_perks", "").orEmpty().split(',').filter { it.isNotBlank() }.take(3).forEach { perkChoices.add(ForgePerk.valueOf(it)) }
            storedTraps.clear()
            val saveVersion = preferences.getInt("run_save_version", 1)
            preferences.getString("run_inventory", "").orEmpty().split(';').filter { it.isNotBlank() }.take(20).forEach {
                val values = it.split(',')
                if (saveVersion >= 3 && values.size >= 4) {
                    storedTraps.add(StoredTrap(TrapKind.valueOf(values[0]), values[1].toInt().coerceIn(1, 3), values[2].toInt().coerceIn(0, 999), if (values[3] != "NONE") Imbuement.valueOf(values[3]) else null))
                } else {
                    repeat(values.getOrElse(1) { "0" }.toInt().coerceIn(0, 4)) { storedTraps.add(StoredTrap(TrapKind.valueOf(values[0]))) }
                }
            }
            while (storedTraps.size > 20) storedTraps.removeAt(storedTraps.lastIndex)
            supplies.clear()
            preferences.getString("run_supplies", "").orEmpty().split(';').filter { it.isNotBlank() }.forEach {
                val values = it.split(',')
                val item = CraftedItem.valueOf(values[0])
                supplies[item] = values[1].toInt().coerceIn(0, item.maxStack)
            }
            gameMode = try { GameMode.valueOf(preferences.getString("run_mode", GameMode.ENDLESS.name).orEmpty()) } catch (_: Exception) { GameMode.ENDLESS }
            challengeModifier = try { ChallengeModifier.valueOf(preferences.getString("run_modifier", ChallengeModifier.NONE.name).orEmpty()) } catch (_: Exception) { ChallengeModifier.NONE }
            runSeed = preferences.getLong("run_seed", 7331L)
            gold = preferences.getInt("run_gold", STARTING_BLOCKS).coerceIn(0, 2_000_000_000)
            maxCore = preferences.getInt("run_max_core", STARTING_CORE).coerceIn(1, 999)
            lives = preferences.getInt("run_lives", STARTING_CORE).coerceIn(1, maxCore)
            score = preferences.getInt("run_score", 0).coerceIn(0, 2_000_000_000)
            waveNumber = preferences.getInt("run_wave", 0).coerceIn(0, 2_000_000_000)
            forgeCharges = preferences.getInt("run_forge_charges", 0).coerceIn(0, 2_000_000_000)
            evolutionCores = preferences.getInt("run_evolution_cores", 0).coerceIn(0, 2_000_000_000)
            salvageParts = preferences.getInt("run_salvage_parts", 0).coerceIn(0, 2_000_000_000)
            growthEssence = preferences.getInt("run_growth_essence", 0).coerceIn(0, 2_000_000_000)
            surveyLensWaves = preferences.getInt("run_survey_lens_waves", 0).coerceIn(0, 3)
            phaseBarrierReady = preferences.getBoolean("run_phase_barrier", false)
            splinterBraceReady = preferences.getBoolean("run_splinter_brace", false)
            nextTrapId = (traps.maxByOrNull { it.id }?.id ?: 0) + 1
            nextCorruptionId = (corruptions.maxByOrNull { it.id }?.id ?: 0) + 1
            nextEnemyId = 1
            enemies.clear()
            pendingSpawns.clear()
            projectiles.clear()
            particles.clear()
            impactEffects.clear()
            floatingLabels.clear()
            waveQueue.clear()
            pathComplete = true
            selectedTool = if (challengeModifier == ChallengeModifier.TRAPS_ONLY) BuildTool.SPIKES else BuildTool.BOLT
            selectedTower = null
            selectedTrap = null
            selectedUtility = null
            selectedUtilityKind = null
            selectedStoredTrapIndex = -1
            selectedCorruption = null
            buildPage = if (challengeModifier == ChallengeModifier.TRAPS_ONLY) BuildPage.TRAPS else BuildPage.TOWERS
            rebuildToolRects()
            phase = if (perkChoices.size == 3) GamePhase.PERK_DRAFT else GamePhase.BUILD
            setBanner(if (phase == GamePhase.PERK_DRAFT) "CHOOSE YOUR FORGE PERK" else "RUN RESTORED  WAVE ${waveNumber + 1} AWAITS", 3f)
            audio.play("build", 0.50f, 1.05f)
        } catch (_: Exception) {
            clearSavedRun()
            newRun()
            setBanner("SAVE COULD NOT BE RESTORED  NEW RUN STARTED", 2.7f)
        }
    }

    private fun isValidSavedPath(path: List<GridCell>): Boolean {
        if (path.size < COLS || path.size > COLS * ROWS) return false
        if (path.first() != GridCell(0, START_ROW) || path.last() != GridCell(COLS - 1, START_ROW)) return false
        if (path.toSet().size != path.size) return false
        return path.all { it.col in 0 until COLS && it.row in 0 until ROWS } && path.zipWithNext().all { abs(it.first.col - it.second.col) + abs(it.first.row - it.second.row) == 1 }
    }

    private fun clearSavedRun() {
        val editor = preferences.edit()
        arrayOf("run_path", "run_towers", "run_traps", "run_utilities", "run_corruptions", "run_perks", "run_pending_perks", "run_inventory", "run_supplies", "run_gold", "run_lives", "run_max_core", "run_score", "run_wave", "run_forge_charges", "run_evolution_cores", "run_salvage_parts", "run_growth_essence", "run_survey_lens_waves", "run_phase_barrier", "run_mode", "run_modifier", "run_seed", "run_save_version").forEach { editor.remove(it) }
        editor.putBoolean("has_saved_run", false).apply()
        savedRunAvailable = false
    }

    private fun newRun(mode: GameMode = GameMode.ENDLESS, seed: Long = 7331L, modifier: ChallengeModifier = ChallengeModifier.NONE) {
        clearSavedRun()
        gameMode = mode
        runSeed = seed
        challengeModifier = if (mode == GameMode.ENDLESS) ChallengeModifier.NONE else modifier
        phase = GamePhase.DIG
        selectedTool = if (challengeModifier == ChallengeModifier.TRAPS_ONLY) BuildTool.SPIKES else BuildTool.DIG
        selectedTower = null
        selectedTrap = null
        selectedUtility = null
        selectedUtilityKind = null
        selectedStoredTrapIndex = -1
        selectedCorruption = null
        evolutionTower = null
        imbuementTower = null
        imbuementTrap = null
        imbuementUtility = null
        buildPage = if (challengeModifier == ChallengeModifier.TRAPS_ONLY) BuildPage.TRAPS else BuildPage.TOWERS
        rebuildToolRects()
        pathCells.clear()
        pathCells.add(GridCell(0, START_ROW))
        reforgeOriginalPath.clear()
        towers.clear()
        traps.clear()
        utilities.clear()
        storedTraps.clear()
        supplies.clear()
        corruptions.clear()
        perks.clear()
        perkChoices.clear()
        enemies.clear()
        pendingSpawns.clear()
        projectiles.clear()
        particles.clear()
        impactEffects.clear()
        floatingLabels.clear()
        waveQueue.clear()
        gold = STARTING_BLOCKS
        maxCore = if (challengeModifier == ChallengeModifier.FRAGILE_CORE) 5 else STARTING_CORE
        lives = maxCore
        score = 0
        waveNumber = 0
        nextEnemyId = 1
        nextTrapId = 1
        nextCorruptionId = 1
        forgeCharges = 0
        evolutionCores = 0
        salvageParts = 0
        growthEssence = 0
        surveyLensWaves = 0
        phaseBarrierReady = false
        splinterBraceReady = false
        coolingImmunity.clear()
        resetCamera()
        reforgeCost = 0
        pathComplete = false
        diggingGesture = false
        waveTheme = "FORGE THE FIRST ROUTE"
        bannerText = if (mode == GameMode.ENDLESS) "WIDE HOLD  DRAG GATE TO CORE  PINCH TO ZOOM" else "${challengeModifier.title.uppercase()}  SEED $runSeed"
        bannerDuration = 3.4f
        bannerTimer = 3.4f
        goldPulse = 0f
        forgePulse = 0f
        lastDisplayedGold = gold
        lastDisplayedForge = forgeCharges
        audio.play("ui_click", 0.5f, 1.04f)
    }

    private fun startDailyChallenge() {
        val calendar = Calendar.getInstance()
        val seed = calendar.get(Calendar.YEAR).toLong() * 10000L + (calendar.get(Calendar.MONTH) + 1).toLong() * 100L + calendar.get(Calendar.DAY_OF_MONTH).toLong()
        newRun(GameMode.DAILY, seed, modifierForSeed(seed))
    }

    private fun startCustomChallenge() {
        var seed = 0L
        for (digit in challengeDigits) seed = seed * 10L + digit
        if (seed == 0L) seed = 1L
        newRun(GameMode.CUSTOM, seed, modifierForSeed(seed))
    }

    private fun modifierForSeed(seed: Long): ChallengeModifier {
        val modifiers = ChallengeModifier.values().filter { it != ChallengeModifier.NONE }
        return modifiers[((seed and Long.MAX_VALUE) % modifiers.size.toLong()).toInt()]
    }

    private fun restartCurrentRun() {
        val mode = gameMode
        val seed = runSeed
        val modifier = challengeModifier
        newRun(mode, seed, modifier)
    }

    private fun returnToTitle() {
        if ((phase == GamePhase.BUILD || (phase == GamePhase.PAUSED && phaseBeforePause == GamePhase.BUILD)) && pathComplete) saveRun()
        phase = GamePhase.TITLE
        enemies.clear()
        pendingSpawns.clear()
        projectiles.clear()
        selectedTower = null
        selectedTrap = null
        selectedUtility = null
        selectedUtilityKind = null
        selectedStoredTrapIndex = -1
        selectedCorruption = null
        evolutionTower = null
        imbuementTower = null
        imbuementTrap = null
        imbuementUtility = null
        bannerTimer = 0f
        savedRunAvailable = prefBoolean("has_saved_run", false)
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
        utilities.clear()
        storedTraps.clear()
        supplies.clear()
        corruptions.clear()
        salvageParts = 0
        growthEssence = 0
        surveyLensWaves = 0
        forgeCharges = 0
        evolutionCores = 0
        gold = STARTING_BLOCKS
        pathComplete = false
        diggingGesture = false
        phase = GamePhase.DIG
        selectedTool = if (challengeModifier == ChallengeModifier.TRAPS_ONLY) BuildTool.SPIKES else BuildTool.DIG
        selectedTower = null
        selectedTrap = null
        selectedUtility = null
        selectedUtilityKind = null
        selectedStoredTrapIndex = -1
        selectedCorruption = null
        evolutionTower = null
        imbuementTower = null
        imbuementTrap = null
        imbuementUtility = null
        buildPage = if (challengeModifier == ChallengeModifier.TRAPS_ONLY) BuildPage.TRAPS else BuildPage.TOWERS
        rebuildToolRects()
        setBanner("PATH RESET  DRAG TO THE CORE", 2.2f)
        audio.play("dig", 0.4f, 0.78f)
    }

    private fun setBanner(message: String, duration: Float) {
        bannerText = message
        bannerDuration = max(0.35f, duration)
        bannerTimer = bannerDuration
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

            // F0 camera: pinch-zoom + drag-pan on the board viewport (chrome stays fixed)
            val playPhases = phase == GamePhase.BUILD || phase == GamePhase.DIG || phase == GamePhase.WAVE ||
                phase == GamePhase.REFORGE
            if (playPhases) {
                if (event.actionMasked == MotionEvent.ACTION_DOWN && inBoardViewport(x, y) && event.pointerCount == 1) {
                    panLastX = x
                    panLastY = y
                    // Allow pan when zoomed; still start dig/build on tap if no drag
                    cameraGesture = cameraZoom > 1.02f
                    if (event.actionMasked == MotionEvent.ACTION_DOWN) suppressGridTap = false
                }
                if (handleCameraTouch(event)) {
                    if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                        // keep suppress one frame; cleared below on non-camera path
                    }
                    return true
                }
            }

            if (phase == GamePhase.TITLE) {
                if (event.action == MotionEvent.ACTION_UP) {
                    when {
                        titlePlayRect.contains(x, y) -> newRun()
                        titleContinueRect.contains(x, y) && savedRunAvailable -> loadSavedRun()
                        titleChallengeRect.contains(x, y) -> phase = GamePhase.CHALLENGE_MENU
                        titleSoundRect.contains(x, y) -> audio.toggle()
                    }
                }
                return true
            }

            if (phase == GamePhase.CHALLENGE_MENU) {
                if (event.action == MotionEvent.ACTION_UP) {
                    when {
                        challengeDailyRect.contains(x, y) -> startDailyChallenge()
                        challengeSeedStartRect.contains(x, y) -> startCustomChallenge()
                        challengeBackRect.contains(x, y) -> phase = GamePhase.TITLE
                        else -> seedDigitRects.forEachIndexed { index, rect -> if (rect.contains(x, y)) challengeDigits[index] = (challengeDigits[index] + 1) % 10 }
                    }
                }
                return true
            }

            if (phase == GamePhase.PERK_DRAFT) {
                if (event.action == MotionEvent.ACTION_UP) perkRects.forEachIndexed { index, rect -> if (rect.contains(x, y) && index < perkChoices.size) choosePerk(perkChoices[index]) }
                return true
            }

            if (phase == GamePhase.EVOLUTION_DRAFT) {
                if (event.action == MotionEvent.ACTION_UP) evolutionRects.forEachIndexed { index, rect -> if (rect.contains(x, y)) chooseEvolution(index) }
                return true
            }

            if (phase == GamePhase.WORKSHOP) {
                if (event.action == MotionEvent.ACTION_UP) handleWorkshopTouch(x, y)
                return true
            }

            if (phase == GamePhase.VICTORY || phase == GamePhase.GAME_OVER) {
                if (event.action == MotionEvent.ACTION_UP) {
                    if (endPrimaryRect.contains(x, y)) restartCurrentRun() else if (endSecondaryRect.contains(x, y)) returnToTitle()
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
                if (resetPathRect.contains(x, y)) {
                    if (phase == GamePhase.REFORGE) cancelReforge()
                    else if (phase == GamePhase.BUILD && waveNumber == 0) resetPath()
                    else if (phase == GamePhase.BUILD) startReforge()
                    return true
                }
                if (primaryActionRect.contains(x, y)) {
                    if (phase == GamePhase.BUILD) startWave() else if (phase == GamePhase.REFORGE) confirmReforge()
                    return true
                }
            }

            if ((selectedTower != null || selectedTrap != null || selectedUtility != null || selectedCorruption != null) && phase == GamePhase.BUILD && event.action == MotionEvent.ACTION_UP) {
                if (backRect.contains(x, y)) {
                    selectedTower = null
                    selectedTrap = null
                    selectedUtility = null
                    selectedCorruption = null
                    audio.play("ui_click", 0.3f, 1f)
                    return true
                }
                if (upgradeRect.contains(x, y)) {
                    when {
                        selectedCorruption != null -> cleanseSelectedCorruption()
                        selectedUtility != null -> upgradeSelectedUtility()
                        else -> upgradeSelectedDefense()
                    }
                    return true
                }
                if (storeRect.contains(x, y)) {
                    val tower = selectedTower
                    when {
                        tower != null && tower.canEvolve() -> if (evolutionCores > 0) openEvolutionDraft(tower) else setBanner("DEFEAT AN OVERGROWTH BOSS FOR AN EVOLUTION CORE", 2f)
                        selectedTrap != null -> storeSelectedTrap()
                        selectedUtility?.kind == UtilityKind.FORGE_WORKSHOP -> openWorkshop()
                    }
                    return true
                }
                if (imbueRect.contains(x, y)) {
                    openImbuement(selectedTower, selectedTrap, selectedUtility)
                    return true
                }
                if (sellRect.contains(x, y)) {
                    if (selectedUtility != null) recycleSelectedUtility() else recycleSelectedDefense()
                    return true
                }
            }

            if (phase == GamePhase.BUILD && event.action == MotionEvent.ACTION_UP) {
                if (towerPageRect.contains(x, y)) {
                    if (challengeModifier == ChallengeModifier.TRAPS_ONLY) {
                        setBanner("TRAPS ONLY CHALLENGE", 1.4f)
                        return true
                    }
                    if (buildPage == BuildPage.TOWERS) towerPageIndex = (towerPageIndex + 1) % 4 else buildPage = BuildPage.TOWERS
                    if (selectedTool.ordinal >= BuildTool.SPIKES.ordinal) selectedTool = BuildTool.BOLT
                    clearBuildSelections()
                    rebuildToolRects()
                    audio.play("ui_click", 0.28f, 1.02f)
                    return true
                }
                if (trapPageRect.contains(x, y)) {
                    if (challengeModifier == ChallengeModifier.TOWERS_ONLY) {
                        setBanner("TOWERS ONLY CHALLENGE", 1.4f)
                        return true
                    }
                    buildPage = BuildPage.TRAPS
                    if (selectedTool.ordinal < BuildTool.SPIKES.ordinal) selectedTool = BuildTool.SPIKES
                    clearBuildSelections()
                    rebuildToolRects()
                    audio.play("ui_click", 0.28f, 0.96f)
                    return true
                }
                if (utilityPageRect.contains(x, y)) {
                    if (buildPage == BuildPage.UTILITIES) utilityPageIndex = (utilityPageIndex + 1) % 5 else buildPage = BuildPage.UTILITIES
                    clearBuildSelections()
                    rebuildToolRects()
                    audio.play("ui_click", 0.28f, 1.08f)
                    return true
                }
                if (cachePageRect.contains(x, y)) {
                    if (buildPage == BuildPage.CACHE) {
                        val pages = max(1, (storedTraps.size + 4) / 5)
                        cachePageIndex = (cachePageIndex + 1) % pages
                    } else buildPage = BuildPage.CACHE
                    clearBuildSelections()
                    rebuildToolRects()
                    audio.play("ui_click", 0.28f, 0.92f)
                    return true
                }
                for (entry in toolRects) if (entry.second.contains(x, y)) { selectTool(entry.first); return true }
                for (entry in utilityRects) if (entry.second.contains(x, y)) {
                    clearBuildSelections()
                    selectedUtilityKind = entry.first
                    audio.play("ui_click", 0.28f, 1.04f)
                    return true
                }
                for (entry in cacheRects) if (entry.second.contains(x, y)) {
                    clearBuildSelections()
                    selectedStoredTrapIndex = entry.first
                    audio.play("ui_click", 0.28f, 0.98f)
                    return true
                }
            }

            val cell = screenToCell(x, y)
            if (cell != null && !suppressGridTap) {
                if ((phase == GamePhase.DIG || phase == GamePhase.REFORGE) && event.action == MotionEvent.ACTION_DOWN) {
                    diggingGesture = true
                    cameraGesture = false
                    if (phase == GamePhase.REFORGE) extendReforgePath(cell) else extendPath(cell)
                    return true
                }
                if (diggingGesture && event.action == MotionEvent.ACTION_MOVE) {
                    if (phase == GamePhase.REFORGE) extendReforgePath(cell) else extendPath(cell)
                    return true
                }
                if (event.action == MotionEvent.ACTION_UP) {
                    handleGridTap(cell)
                    suppressGridTap = false
                    return true
                }
            }
            if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL) {
                suppressGridTap = false
            }
            return true
        }
    }

    private fun clearBuildSelections() {
        selectedTower = null
        selectedTrap = null
        selectedUtility = null
        selectedCorruption = null
        selectedUtilityKind = null
        selectedStoredTrapIndex = -1
    }

    private fun selectTool(tool: BuildTool) {
        if (phase != GamePhase.BUILD) return
        if (challengeModifier == ChallengeModifier.TRAPS_ONLY && tool.ordinal < BuildTool.SPIKES.ordinal) return
        if (challengeModifier == ChallengeModifier.TOWERS_ONLY && tool.ordinal >= BuildTool.SPIKES.ordinal) return
        clearBuildSelections()
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
        val currentPathLimit = currentPathLimit()
        if (pathCells.size >= currentPathLimit) {
            setBanner("PATH LIMIT $currentPathLimit  CONNECT TO THE CORE", 1.7f)
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
            selectedTool = if (challengeModifier == ChallengeModifier.TRAPS_ONLY) BuildTool.SPIKES else BuildTool.BOLT
            buildPage = if (challengeModifier == ChallengeModifier.TRAPS_ONLY) BuildPage.TRAPS else BuildPage.TOWERS
            rebuildToolRects()
            setBanner("PATH LOCKED  BUILD YOUR DEFENSE", 2.6f)
            saveRun()
            audio.play("build", 0.55f, 1f)
        }
    }

    private fun handleGridTap(cell: GridCell) {
        val existingCorruption = findCorruption(cell.col, cell.row)
        if (existingCorruption != null && phase == GamePhase.BUILD) {
            selectedCorruption = existingCorruption
            selectedTower = null
            selectedTrap = null
            selectedUtility = null
            audio.play("ui_click", 0.28f, 0.84f)
            return
        }
        val existingTower = findTower(cell.col, cell.row)
        if (existingTower != null) {
            selectedTower = existingTower
            selectedTrap = null
            selectedUtility = null
            selectedCorruption = null
            audio.play("ui_click", 0.28f, 1.12f)
            return
        }
        val existingUtility = findUtility(cell.col, cell.row)
        if (existingUtility != null) {
            selectedUtility = existingUtility
            selectedTower = null
            selectedTrap = null
            selectedCorruption = null
            audio.play("ui_click", 0.28f, 0.92f)
            return
        }
        val existingTrap = findTrap(cell.col, cell.row)
        if (existingTrap != null) {
            selectedTrap = existingTrap
            selectedTower = null
            selectedUtility = null
            selectedCorruption = null
            audio.play("ui_click", 0.28f, 1.06f)
            return
        }
        if (phase != GamePhase.BUILD) return
        selectedTower = null
        selectedTrap = null
        selectedUtility = null
        selectedCorruption = null
        if (buildPage == BuildPage.UTILITIES && selectedUtilityKind != null) {
            placeUtility(cell, selectedUtilityKind!!)
            return
        }
        if (buildPage == BuildPage.CACHE && selectedStoredTrapIndex in storedTraps.indices) {
            placeStoredTrap(cell)
            return
        }
        when (selectedTool) {
            BuildTool.BOLT -> placeTower(cell, TowerKind.BOLT)
            BuildTool.FROST -> placeTower(cell, TowerKind.FROST)
            BuildTool.CANNON -> placeTower(cell, TowerKind.CANNON)
            BuildTool.EMBER -> placeTower(cell, TowerKind.EMBER)
            BuildTool.BEACON -> placeTower(cell, TowerKind.BEACON)
            BuildTool.THORN -> placeTower(cell, TowerKind.THORN)
            BuildTool.LANCE -> placeTower(cell, TowerKind.LANCE)
            BuildTool.MIRE -> placeTower(cell, TowerKind.MIRE)
            BuildTool.GALE -> placeTower(cell, TowerKind.GALE)
            BuildTool.SUNFORGE -> placeTower(cell, TowerKind.SUNFORGE)
            BuildTool.LODESTONE -> placeTower(cell, TowerKind.LODESTONE)
            BuildTool.HOWL -> placeTower(cell, TowerKind.HOWL)
            BuildTool.VITRIOL -> placeTower(cell, TowerKind.VITRIOL)
            BuildTool.GRAVEBOLT -> placeTower(cell, TowerKind.GRAVEBOLT)
            BuildTool.AEGIS_LOOM -> placeTower(cell, TowerKind.AEGIS_LOOM)
            BuildTool.SPIKES -> placeTrap(cell, TrapKind.SPIKE)
            BuildTool.ROOT -> placeTrap(cell, TrapKind.ROOT)
            BuildTool.RUNE -> placeTrap(cell, TrapKind.EMBER)
            BuildTool.ARC -> placeTrap(cell, TrapKind.ARC)
            BuildTool.CRUSHER -> placeTrap(cell, TrapKind.CRUSHER)
            BuildTool.DIG -> Unit
        }
    }

    private fun placeTower(cell: GridCell, kind: TowerKind) {
        if (challengeModifier == ChallengeModifier.TRAPS_ONLY) return
        if (isPathCell(cell) || findTower(cell.col, cell.row) != null || findTrap(cell.col, cell.row) != null || findUtility(cell.col, cell.row) != null || findCorruption(cell.col, cell.row)?.kind == CorruptionKind.BROOD_NEST) {
            setBanner("TOWERS NEED A FREE TERRAIN BLOCK", 1.5f)
            audio.play("ui_click", 0.24f, 0.7f)
            return
        }
        var cost = kind.cost
        if (findCorruption(cell.col, cell.row)?.kind == CorruptionKind.THORN_SOIL) cost += 30
        if (gold < cost) {
            setBanner("NEED $cost BLOCKS", 1.5f)
            audio.play("ui_click", 0.24f, 0.68f)
            return
        }
        gold -= cost
        val tower = Tower(cell.col, cell.row, kind)
        towers.add(tower)
        score = safeAdd(score, 20)
        burst(cell.col + 0.5f, cell.row + 0.5f, kind.accent, 12, 0.8f)
        audio.play("build", 0.42f, 0.92f + kind.ordinal * 0.07f)
        setBanner("${kind.title.uppercase()} ONLINE", 1.2f)
        saveRun()
    }

    private fun placeTrap(cell: GridCell, kind: TrapKind) {
        if (challengeModifier == ChallengeModifier.TOWERS_ONLY) return
        if (!isPathCell(cell) || cell == pathCells.first() || cell == pathCells.last() || findTrap(cell.col, cell.row) != null) {
            setBanner("TRAPS GO ON AN EMPTY PATH BLOCK", 1.5f)
            audio.play("ui_click", 0.24f, 0.7f)
            return
        }
        val cost = kind.cost
        if (gold < cost) {
            setBanner("NEED $cost BLOCKS", 1.5f)
            audio.play("ui_click", 0.24f, 0.68f)
            return
        }
        gold -= cost
        traps.add(SpikeTrap(nextTrapId++, cell.col, cell.row, kind))
        burst(cell.col + 0.5f, cell.row + 0.5f, kind.accent, 9, 0.65f)
        audio.play("build", 0.34f, 1.12f + kind.ordinal * 0.05f)
        setBanner("${kind.title.uppercase()} ARMED", 1.1f)
        saveRun()
    }

    private fun upgradeSelectedDefense() {
        val tower = selectedTower
        val trap = selectedTrap
        var cost = tower?.upgradeCost() ?: trap?.upgradeCost() ?: return
        if ((tower?.level ?: trap?.level ?: 1) >= 3) cost = max(1, cost - (cost * perkCount(ForgePerk.EFFICIENT_OVERCHARGE) * 0.20f).toInt())
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
        setBanner("${title.uppercase()}  $rank", 1.5f)
        saveRun()
    }

    private fun recycleSelectedDefense() {
        if (challengeModifier == ChallengeModifier.NO_RECYCLING) {
            setBanner("NO RECYCLING CHALLENGE", 1.5f)
            return
        }
        val tower = selectedTower
        val trap = selectedTrap
        val value = tower?.sellValue(recyclingMultiplier()) ?: trap?.sellValue(recyclingMultiplier()) ?: return
        gold = safeAdd(gold, value)
        val rankParts = tower?.let { it.level + it.overcharge / 2 } ?: trap?.let { it.level + it.overcharge / 2 } ?: 1
        val yardBonus = utilities.filter { it.kind == UtilityKind.SALVAGE_YARD }.map { utilityPowerLevel(it) }.maxOrNull() ?: 0
        val parts = min(20, rankParts + yardBonus)
        salvageParts = safeAdd(salvageParts, parts)
        if (tower != null) towers.remove(tower) else if (trap != null) traps.remove(trap)
        selectedTower = null
        selectedTrap = null
        audio.play("dig", 0.38f, 0.72f)
        setBanner("RECYCLED  +$value BLOCKS  +$parts PARTS", 1.4f)
        saveRun()
    }

    private fun storeSelectedTrap() {
        val trap = selectedTrap ?: return
        if (storedTraps.size >= cacheCapacity()) {
            setBanner("CACHE FULL  ${storedTraps.size}/${cacheCapacity()}", 1.6f)
            return
        }
        val wrapped = supplyCount(CraftedItem.RECOVERY_WRAP) > 0
        val cost = if (wrapped) 0 else trapStorageCost(trap)
        if (gold < cost) {
            setBanner("NEED $cost BLOCKS TO STORE", 1.6f)
            return
        }
        gold -= cost
        if (wrapped) consumeSupply(CraftedItem.RECOVERY_WRAP)
        storedTraps.add(StoredTrap(trap.kind, trap.level, trap.overcharge, trap.imbuement))
        traps.remove(trap)
        selectedTrap = null
        selectedStoredTrapIndex = storedTraps.lastIndex
        buildPage = BuildPage.CACHE
        rebuildToolRects()
        setBanner("${trap.kind.title.uppercase()} CACHED  -$cost BLOCKS", 1.8f)
        saveRun()
    }

    private fun placeStoredTrap(cell: GridCell) {
        if (challengeModifier == ChallengeModifier.TOWERS_ONLY) return
        if (selectedStoredTrapIndex !in storedTraps.indices) return
        if (!isPathCell(cell) || cell == pathCells.first() || cell == pathCells.last() || findTrap(cell.col, cell.row) != null) {
            setBanner("CACHED TRAPS NEED AN EMPTY PATH BLOCK", 1.6f)
            return
        }
        val stored = storedTraps.removeAt(selectedStoredTrapIndex)
        val trap = SpikeTrap(nextTrapId++, cell.col, cell.row, stored.kind)
        trap.level = stored.level
        trap.overcharge = stored.overcharge
        trap.imbuement = stored.imbuement
        traps.add(trap)
        selectedStoredTrapIndex = -1
        cachePageIndex = min(cachePageIndex, max(0, (storedTraps.size - 1) / 5))
        rebuildToolRects()
        burst(cell.col + 0.5f, cell.row + 0.5f, stored.kind.accent, 13, 0.8f)
        setBanner("${stored.kind.title.uppercase()} RECOVERED  ${stored.rankLabel()}", 1.7f)
        saveRun()
    }

    private fun utilityUnlocked(kind: UtilityKind): Boolean {
        return waveNumber >= 10 || kind == UtilityKind.BLOCK_GENERATOR || kind == UtilityKind.SURVEYOR_STATION || kind == UtilityKind.SALVAGE_YARD
    }

    private fun placeUtility(cell: GridCell, kind: UtilityKind) {
        if (!utilityUnlocked(kind)) {
            setBanner("DEFEAT THE FIRST OVERGROWTH TO UNLOCK", 1.8f)
            return
        }
        if (utilities.size >= utilityCapacity()) {
            setBanner("UTILITY CAPACITY ${utilityCapacity()} REACHED", 1.7f)
            return
        }
        val copies = utilities.count { it.kind == kind }
        if ((kind != UtilityKind.BLOCK_GENERATOR && copies >= 1) || (kind == UtilityKind.BLOCK_GENERATOR && copies >= 2)) {
            setBanner("UTILITY LIMIT REACHED", 1.5f)
            return
        }
        if (isPathCell(cell) || findTower(cell.col, cell.row) != null || findUtility(cell.col, cell.row) != null || findCorruption(cell.col, cell.row) != null) {
            setBanner("UTILITIES NEED CLEAN FREE TERRAIN", 1.5f)
            return
        }
        if (gold < kind.cost) {
            setBanner("NEED ${kind.cost} BLOCKS", 1.5f)
            return
        }
        gold -= kind.cost
        val utility = Utility(cell.col, cell.row, kind)
        utilities.add(utility)
        selectedUtility = utility
        selectedUtilityKind = null
        burst(cell.col + 0.5f, cell.row + 0.5f, kind.accent, 18, 1.0f)
        setBanner("${kind.title.uppercase()} ONLINE", 1.7f)
        saveRun()
    }

    private fun upgradeSelectedUtility() {
        val utility = selectedUtility ?: return
        if (utility.level >= 3) {
            setBanner("UTILITY AT MAXIMUM LEVEL", 1.4f)
            return
        }
        val hasGearset = supplyCount(CraftedItem.UTILITY_GEARSET) > 0
        val cost = if (hasGearset) max(1, utility.upgradeCost() / 2) else utility.upgradeCost()
        if (gold < cost) {
            setBanner("NEED $cost BLOCKS TO UPGRADE", 1.5f)
            return
        }
        gold -= cost
        if (hasGearset) consumeSupply(CraftedItem.UTILITY_GEARSET)
        utility.level += 1
        burst(utility.col + 0.5f, utility.row + 0.5f, utility.kind.accent, 20, 1.2f)
        setBanner("${utility.kind.title.uppercase()}  LEVEL ${utility.level}", 1.7f)
        rebuildToolRects()
        saveRun()
    }

    private fun recycleSelectedUtility() {
        val utility = selectedUtility ?: return
        if (challengeModifier == ChallengeModifier.NO_RECYCLING) {
            setBanner("NO RECYCLING CHALLENGE", 1.5f)
            return
        }
        val invested = utility.kind.cost + (utility.level - 1) * (utility.kind.cost / 2 + 70)
        val value = (invested * recyclingMultiplier()).toInt()
        gold = safeAdd(gold, value)
        val parts = utility.level + (utilities.filter { it.kind == UtilityKind.SALVAGE_YARD }.map { utilityPowerLevel(it) }.maxOrNull() ?: 0)
        salvageParts = safeAdd(salvageParts, parts)
        utilities.remove(utility)
        selectedUtility = null
        rebuildToolRects()
        setBanner("UTILITY RECYCLED  +$value BLOCKS  +$parts PARTS", 1.8f)
        saveRun()
    }

    private fun openWorkshop() {
        if (workshopLevel() <= 0) return
        phase = GamePhase.WORKSHOP
        workshopTab = WorkshopTab.CRAFT
        workshopPageIndex = 0
        audio.play("build", 0.45f, 0.82f)
    }

    private fun closeWorkshop() {
        phase = GamePhase.BUILD
        imbuementTower = null
        imbuementTrap = null
        imbuementUtility = null
        setBanner("FORGEWORKS CLOSED", 1.2f)
        saveRun()
    }

    private fun craftItem(item: CraftedItem) {
        if (workshopLevel() < item.workshopLevel) {
            setBanner("WORKSHOP LEVEL ${item.workshopLevel} REQUIRED", 1.6f)
            return
        }
        if (supplyCount(item) >= item.maxStack) {
            setBanner("${item.title.uppercase()} STACK FULL", 1.4f)
            return
        }
        val blockCost = craftedBlockCost(item)
        if (gold < blockCost || salvageParts < item.partCost || growthEssence < item.essenceCost) {
            setBanner("NEED $blockCost BLOCKS  ${item.partCost} PARTS  ${item.essenceCost} ESSENCE", 2f)
            return
        }
        gold -= blockCost
        salvageParts -= item.partCost
        growthEssence -= item.essenceCost
        supplies[item] = supplyCount(item) + 1
        setBanner("CRAFTED  ${item.title.uppercase()}", 1.6f)
        audio.play("build", 0.42f, 1.18f)
        saveRun()
    }

    private fun useSupply(item: CraftedItem) {
        if (supplyCount(item) <= 0) return
        when (item) {
            CraftedItem.CORE_PATCH -> {
                if (lives >= maxCore) { setBanner("CORE ALREADY FULL", 1.4f); return }
                lives += 1
                consumeSupply(item)
                setBanner("CORE PATCHED  $lives/$maxCore", 1.5f)
            }
            CraftedItem.SURVEY_LENS -> {
                surveyLensWaves = 3
                consumeSupply(item)
                setBanner("THREE WAVE SIGNALS REVEALED", 1.7f)
            }
            CraftedItem.TRAP_REFIT_KIT -> {
                val index = storedTraps.indices.filter { storedTraps[it].level < 3 }.minByOrNull { storedTraps[it].level }
                if (index == null) { setBanner("NO CACHED TRAP CAN BE REFIT", 1.5f); return }
                storedTraps[index].level += 1
                consumeSupply(item)
                rebuildToolRects()
                setBanner("${storedTraps[index].kind.title.uppercase()} REFIT  LEVEL ${storedTraps[index].level}", 1.7f)
            }
            CraftedItem.SPLINTER_BRACE -> {
                if (splinterBraceReady) { setBanner("SPLINTER BRACE ALREADY ARMED", 1.4f); return }
                splinterBraceReady = true
                consumeSupply(item)
                setBanner("SPLINTER BRACE ARMED", 1.6f)
                audio.play("build", 0.45f, 1.1f)
            }
            CraftedItem.RESIN_SEAL -> {
                if (phaseBarrierReady) { setBanner("CORE BARRIER ALREADY ARMED", 1.4f); return }
                phaseBarrierReady = true
                consumeSupply(item)
                setBanner("RESIN SEAL ARMED  NEXT LEAK BLOCKED", 1.8f)
                audio.play("build", 0.5f, 0.9f)
            }
            CraftedItem.COOLING_FLASK -> {
                val tower = selectedTower
                if (tower == null) { setBanner("SELECT A TOWER TO COOL", 1.5f); return }
                tower.disabledTimer = 0f
                coolingImmunity[towerKey(tower.col, tower.row)] = 6f
                consumeSupply(item)
                setBanner("TOWER COOLED  HEX WARD 6s", 1.7f)
                floatingLabels.add(FloatingLabel("COOLED", tower.col + 0.5f, tower.row + 0.15f, Color.rgb(93, 220, 255), pop = 1.4f))
                burst(tower.col + 0.5f, tower.row + 0.5f, Color.rgb(93, 220, 255), 18, 1.0f)
                audio.play("frost", 0.4f, 1.2f)
            }
            CraftedItem.OVERCHARGE_CELL -> {
                val tower = selectedTower
                if (tower == null) { setBanner("SELECT A TOWER TO OVERCHARGE", 1.5f); return }
                tower.damageBoostTimer = max(tower.damageBoostTimer, 18f)
                consumeSupply(item)
                setBanner("OVERCHARGE ARMED  18s", 1.7f)
                floatingLabels.add(FloatingLabel("OVERCHARGE", tower.col + 0.5f, tower.row + 0.15f, Color.rgb(255, 186, 70), pop = 1.4f))
                burst(tower.col + 0.5f, tower.row + 0.5f, Color.rgb(255, 186, 70), 20, 1.1f)
                audio.play("ember", 0.4f, 1.15f)
            }
            CraftedItem.FOCUS_LENS -> {
                val tower = selectedTower
                if (tower == null) { setBanner("SELECT A TOWER TO FOCUS", 1.5f); return }
                tower.focusBoostTimer = max(tower.focusBoostTimer, 16f)
                consumeSupply(item)
                setBanner("FOCUS LENS  16s RANGE", 1.7f)
                floatingLabels.add(FloatingLabel("FOCUS", tower.col + 0.5f, tower.row + 0.15f, Color.rgb(100, 200, 255), pop = 1.4f))
                burst(tower.col + 0.5f, tower.row + 0.5f, Color.rgb(100, 200, 255), 18, 1.0f)
                audio.play("frost", 0.4f, 1.2f)
            }
            CraftedItem.SNAP_SPRING -> {
                val trap = selectedTrap
                if (trap == null) { setBanner("SELECT A TRAP TO SNAP", 1.5f); return }
                for (enemy in enemies) enemy.trapTriggerCounts.remove(trap.id)
                trap.pulse = 1f
                trap.activationCount += 1
                enemies.filter { it.targetable && abs(it.x - (trap.col + 0.5f)) < 0.55f && abs(it.y - (trap.row + 0.5f)) < 0.55f }
                    .forEach { damageEnemy(it, effectiveTrapDamage(trap) * 0.85f, trap.kind.accent) }
                consumeSupply(item)
                setBanner("SNAP SPRING  TRAP RESET", 1.6f)
                burst(trap.col + 0.5f, trap.row + 0.5f, trap.kind.accent, 16, 1.0f)
                audio.play("dig", 0.4f, 1.3f)
            }
            CraftedItem.SURVEY_SPIKE -> {
                surveyLensWaves = max(surveyLensWaves, 1)
                gold = safeAdd(gold, 25)
                consumeSupply(item)
                setBanner("SURVEY SPIKE  NEXT THEME PINNED  +25", 1.7f)
                audio.play("build", 0.4f, 1.15f)
            }
            CraftedItem.ROUTE_OIL -> {
                routeOilWaves = max(routeOilWaves, 1)
                consumeSupply(item)
                setBanner("ROUTE OIL  GATE PATH SLOWED NEXT WAVE", 1.8f)
                audio.play("frost", 0.4f, 0.9f)
            }
            CraftedItem.SALT_BUNDLE -> {
                val target = corruptions.minByOrNull {
                    distanceSquared(it.cell.col + 0.5f, it.cell.row + 0.5f, 8f, 4.5f)
                }
                if (target != null) {
                    corruptions.remove(target)
                    floatingLabels.add(FloatingLabel("CLEANSED", target.cell.col + 0.5f, target.cell.row + 0.2f, Color.rgb(240, 240, 255), 0.7f))
                }
                for (tower in towers) {
                    if (tower.disabledTimer > 0f) tower.disabledTimer = max(0f, tower.disabledTimer - 1.5f)
                }
                consumeSupply(item)
                setBanner(if (target != null) "SALT BUNDLE  CORRUPTION CLEANSED" else "SALT BUNDLE  HEX EASED", 1.7f)
                audio.play("build", 0.45f, 1.05f)
            }
            CraftedItem.SCRAP_MAGNET -> {
                scrapMagnetKills = max(scrapMagnetKills, 3)
                consumeSupply(item)
                setBanner("SCRAP MAGNET  +1 PART ON NEXT 3 KILLS", 1.7f)
                audio.play("dig", 0.4f, 1.1f)
            }
            CraftedItem.RECOVERY_WRAP, CraftedItem.PURIFIER_VIAL, CraftedItem.REFORGE_COUPLER, CraftedItem.UTILITY_GEARSET -> setBanner("THIS SUPPLY ACTIVATES AUTOMATICALLY", 1.5f)
            CraftedItem.BLANK_SIGIL -> setBanner("SELECT A LEVEL 3 STRUCTURE AND CHOOSE IMBUE", 1.8f)
        }
        saveRun()
    }

    private fun openImbuement(tower: Tower? = null, trap: SpikeTrap? = null, utility: Utility? = null) {
        if (workshopLevel() < 3) { setBanner("LEVEL 3 FORGE WORKSHOP REQUIRED", 1.7f); return }
        val eligible = (tower?.level ?: trap?.level ?: utility?.level ?: 0) >= 3
        if (!eligible) { setBanner("STRUCTURE MUST REACH LEVEL 3", 1.6f); return }
        if (supplyCount(CraftedItem.BLANK_SIGIL) <= 0 || growthEssence < 1 || gold < 120) {
            setBanner("NEED 1 BLANK SIGIL  1 ESSENCE  120 BLOCKS", 1.9f)
            return
        }
        imbuementTower = tower
        imbuementTrap = trap
        imbuementUtility = utility
        phase = GamePhase.WORKSHOP
        workshopTab = WorkshopTab.IMBUE
        workshopPageIndex = 0
    }

    private fun imbuementCompatible(imbuement: Imbuement): Boolean {
        if (imbuementTower != null) return true
        val trap = imbuementTrap
        if (trap != null) {
            return when (imbuement) {
                Imbuement.CLARITY, Imbuement.WARD, Imbuement.BULWARK -> false
                Imbuement.REACH -> trap.kind == TrapKind.ARC
                else -> true
            }
        }
        val utility = imbuementUtility ?: return false
        return when (imbuement) {
            Imbuement.MIGHT -> true
            Imbuement.TEMPO -> utility.kind == UtilityKind.BLOCK_GENERATOR || utility.kind == UtilityKind.FORGE_WORKSHOP || utility.kind == UtilityKind.ESSENCE_STILL
            Imbuement.REACH -> utility.kind == UtilityKind.PURIFIER_TOTEM || utility.kind == UtilityKind.REFORGE_ANCHOR || utility.kind == UtilityKind.WARD_BEACON || utility.kind == UtilityKind.BATTLE_BANNER || utility.kind == UtilityKind.TRAP_LATTICE
            Imbuement.CLARITY -> true
            Imbuement.ECHOES -> utility.kind == UtilityKind.BLOCK_GENERATOR || utility.kind == UtilityKind.FORGE_WORKSHOP || utility.kind == UtilityKind.PURIFIER_TOTEM || utility.kind == UtilityKind.ESSENCE_STILL
            Imbuement.CONSERVATION -> utility.kind == UtilityKind.CACHE_DEPOT || utility.kind == UtilityKind.FORGE_WORKSHOP || utility.kind == UtilityKind.PURIFIER_TOTEM || utility.kind == UtilityKind.REFORGE_ANCHOR
            Imbuement.WARD -> utility.kind == UtilityKind.WARD_BEACON || utility.kind == UtilityKind.PURIFIER_TOTEM
            Imbuement.LEECH -> false
            Imbuement.SURGE -> utility.kind == UtilityKind.BATTLE_BANNER || utility.kind == UtilityKind.TRAP_LATTICE
            // F8 combat imbuements: combat-tuned effects have no special utility
            // interaction, so they never *fail* compatibility — a generic buff on a
            // utility simply does nothing harmful and is allowed like MIGHT/CLARITY.
            Imbuement.VOLLEY, Imbuement.SIEGE, Imbuement.FORTUNE, Imbuement.BINDING, Imbuement.RIME -> true
            Imbuement.BULWARK -> utility.kind == UtilityKind.WARD_BEACON || utility.kind == UtilityKind.PURIFIER_TOTEM
            Imbuement.HARVEST -> utility.kind == UtilityKind.ESSENCE_STILL || utility.kind == UtilityKind.CACHE_DEPOT
        }
    }

    private fun chooseImbuement(imbuement: Imbuement) {
        if (imbuementTower == null && imbuementTrap == null && imbuementUtility == null) return
        if (!imbuementCompatible(imbuement)) {
            setBanner("${imbuement.title.uppercase()} HAS NO EFFECT ON THIS TARGET", 1.8f)
            return
        }
        if (supplyCount(CraftedItem.BLANK_SIGIL) <= 0 || growthEssence < 1 || gold < 120) return
        gold -= 120
        growthEssence -= 1
        consumeSupply(CraftedItem.BLANK_SIGIL)
        imbuementTower?.imbuement = imbuement
        imbuementTrap?.imbuement = imbuement
        imbuementUtility?.imbuement = imbuement
        if (imbuement == Imbuement.SURGE) {
            imbuementTower?.surgeCharges = 3
            imbuementTrap?.surgeCharges = 3
        }
        val x = imbuementTower?.col ?: imbuementTrap?.col ?: imbuementUtility?.col ?: 0
        val y = imbuementTower?.row ?: imbuementTrap?.row ?: imbuementUtility?.row ?: 0
        burst(x + 0.5f, y + 0.5f, imbuement.accent, 26, 1.4f)
        phase = GamePhase.BUILD
        setBanner("IMBUED WITH ${imbuement.title.uppercase()}", 2f)
        imbuementTower = null
        imbuementTrap = null
        imbuementUtility = null
        saveRun()
    }

    private fun handleWorkshopTouch(x: Float, y: Float) {
        if (workshopBackRect.contains(x, y)) { closeWorkshop(); return }
        for ((tab, rect) in workshopTabRects) if (rect.contains(x, y)) {
            workshopTab = tab
            workshopPageIndex = 0
            return
        }
        val entryCount = when (workshopTab) {
            WorkshopTab.CRAFT, WorkshopTab.SUPPLIES -> CraftedItem.values().size
            WorkshopTab.IMBUE -> if (imbuementTower != null || imbuementTrap != null || imbuementUtility != null) Imbuement.values().size else 0
        }
        val pages = max(1, (entryCount + 3) / 4)
        if (workshopPreviousRect.contains(x, y)) { workshopPageIndex = (workshopPageIndex - 1 + pages) % pages; return }
        if (workshopNextRect.contains(x, y)) { workshopPageIndex = (workshopPageIndex + 1) % pages; return }
        workshopCardRects.forEachIndexed { localIndex, rect ->
            if (!rect.contains(x, y)) return@forEachIndexed
            val index = workshopPageIndex * 4 + localIndex
            when (workshopTab) {
                WorkshopTab.CRAFT -> CraftedItem.values().getOrNull(index)?.let { craftItem(it) }
                WorkshopTab.SUPPLIES -> CraftedItem.values().getOrNull(index)?.let { useSupply(it) }
                WorkshopTab.IMBUE -> Imbuement.values().getOrNull(index)?.let { chooseImbuement(it) }
            }
        }
    }

    private fun startReforge() { 
        if (phase != GamePhase.BUILD || waveNumber == 0) return
        if (forgeCharges <= 0) {
            setBanner("DEFEAT AN OVERGROWTH BOSS FOR FORGE CHARGES", 2f)
            return
        }
        reforgeOriginalPath.clear()
        reforgeOriginalPath.addAll(pathCells)
        pathCells.clear()
        pathCells.add(GridCell(0, START_ROW))
        pathComplete = false
        reforgeCost = 0
        selectedTower = null
        selectedTrap = null
        selectedCorruption = null
        diggingGesture = false
        phase = GamePhase.REFORGE
        setBanner("PREVIEW A NEW ROUTE  TOWERS CANNOT BE CROSSED", 2.5f)
    }

    private fun extendReforgePath(cell: GridCell) {
        if (pathComplete || pathCells.isEmpty()) return
        val last = pathCells.last()
        val distance = abs(cell.col - last.col) + abs(cell.row - last.row)
        if (distance > 1) {
            var safety = 0
            while (!pathComplete && pathCells.last() != cell && safety < COLS + ROWS) {
                val current = pathCells.last()
                val dx = cell.col - current.col
                val dy = cell.row - current.row
                val next = if (abs(dx) >= abs(dy) && dx != 0) GridCell(current.col + if (dx > 0) 1 else -1, current.row) else GridCell(current.col, current.row + if (dy > 0) 1 else -1)
                val before = pathCells.size
                extendReforgePath(next)
                if (pathCells.size == before) break
                safety += 1
            }
            return
        }
        if (pathCells.size >= 2 && cell == pathCells[pathCells.size - 2]) {
            pathCells.removeAt(pathCells.size - 1)
            pathComplete = false
            updateReforgeCost()
            return
        }
        if (distance != 1 || pathCells.contains(cell) || findTower(cell.col, cell.row) != null || findUtility(cell.col, cell.row) != null) return
        val limit = currentPathLimit()
        if (pathCells.size >= limit) {
            setBanner("ROUTE LIMIT $limit", 1.4f)
            return
        }
        val isCore = cell == GridCell(COLS - 1, START_ROW)
        if (cell.col == COLS - 1 && !isCore) return
        pathCells.add(cell)
        pathComplete = isCore
        updateReforgeCost()
        burst(cell.col + 0.5f, cell.row + 0.5f, Color.rgb(190, 244, 78), 4, 0.35f)
    }

    private fun updateReforgeCost() {
        val changedCells = (pathCells.toSet() - reforgeOriginalPath.toSet()) + (reforgeOriginalPath.toSet() - pathCells.toSet())
        var weightedChanges = 0f
        for (cell in changedCells) {
            var multiplier = 1f
            for (anchor in utilities.filter { it.kind == UtilityKind.REFORGE_ANCHOR }) {
                if (distanceSquared(cell.col + 0.5f, cell.row + 0.5f, anchor.col + 0.5f, anchor.row + 0.5f) <= anchor.effectRadius() * anchor.effectRadius()) {
                    var reduction = when (utilityPowerLevel(anchor)) { 1 -> 0.25f; 2 -> 0.35f; 3 -> 0.50f; else -> 0.60f }
                    if (anchor.imbuement == Imbuement.CONSERVATION) reduction += 0.10f
                    multiplier = min(multiplier, 1f - reduction.coerceAtMost(0.75f))
                }
            }
            weightedChanges += multiplier
        }
        reforgeCost = if (changedCells.isEmpty()) 0 else max(1, (weightedChanges / 3f + 0.999f).toInt())
    }

    private fun effectiveReforgeCost(): Int {
        val hasCoupler = supplyCount(CraftedItem.REFORGE_COUPLER) > 0 && reforgeCost > 0
        return if (hasCoupler) max(1, reforgeCost - 2) else reforgeCost
    }

    private fun displacedReforgeTraps(): List<SpikeTrap> = traps.filter { !pathCells.contains(GridCell(it.col, it.row)) }

    private fun reforgeRecoveryCost(displaced: List<SpikeTrap> = displacedReforgeTraps()): Int {
        val wraps = min(displaced.size, supplyCount(CraftedItem.RECOVERY_WRAP))
        var cost = 0
        displaced.forEachIndexed { index, trap -> if (index >= wraps) cost = safeAdd(cost, trapStorageCost(trap)) }
        return cost
    }

    private fun confirmReforge() {
        if (phase != GamePhase.REFORGE) return
        if (!pathComplete) {
            setBanner("CONNECT THE GATE TO THE CORE", 1.6f)
            return
        }
        val displaced = displacedReforgeTraps()
        if (storedTraps.size + displaced.size > cacheCapacity()) {
            setBanner("CACHE FULL  NEED ${storedTraps.size + displaced.size}/${cacheCapacity()} SLOTS", 1.9f)
            return
        }
        val hasCoupler = supplyCount(CraftedItem.REFORGE_COUPLER) > 0 && reforgeCost > 0
        val forgeCost = effectiveReforgeCost()
        if (forgeCost > forgeCharges) {
            setBanner("NEED $forgeCost FORGE CHARGES", 1.7f)
            return
        }
        val wraps = min(displaced.size, supplyCount(CraftedItem.RECOVERY_WRAP))
        val storageCost = reforgeRecoveryCost(displaced)
        if (gold < storageCost) {
            setBanner("NEED $storageCost BLOCKS TO RECOVER ${displaced.size} TRAPS", 2f)
            return
        }
        forgeCharges -= forgeCost
        gold -= storageCost
        if (hasCoupler) consumeSupply(CraftedItem.REFORGE_COUPLER)
        repeat(wraps) { consumeSupply(CraftedItem.RECOVERY_WRAP) }
        for (trap in displaced) storedTraps.add(StoredTrap(trap.kind, trap.level, trap.overcharge, trap.imbuement))
        traps.removeAll(displaced)
        reforgeOriginalPath.clear()
        phase = GamePhase.BUILD
        rebuildToolRects()
        setBanner("REFORGED  -F$forgeCost  -$storageCost BLOCKS  ${displaced.size} CACHED", 2.5f)
        saveRun()
        audio.play("build", 0.62f, 0.86f)
    }

    private fun cancelReforge() {
        if (phase != GamePhase.REFORGE && !(phase == GamePhase.PAUSED && phaseBeforePause == GamePhase.REFORGE)) return
        // Never leave pathCells empty. Almost every renderer and hit-test path calls
        // pathCells.first()/last() unguarded, so an empty route throws NoSuchElementException on
        // the very next frame and the game dies with no obvious repro.
        if (reforgeOriginalPath.isNotEmpty()) {
            pathCells.clear()
            pathCells.addAll(reforgeOriginalPath)
        }
        reforgeOriginalPath.clear()
        pathComplete = true
        diggingGesture = false
        reforgeCost = 0
        phase = GamePhase.BUILD
        setBanner("REFORGE CANCELED", 1.5f)
    }

    private fun corruptionCleanseReduction(corruption: CorruptedCell): Int {
        var reduction = perkCount(ForgePerk.CORRUPTION_WARD)
        for (purifier in utilities.filter { it.kind == UtilityKind.PURIFIER_TOTEM }) {
            if (distanceSquared(purifier.col + 0.5f, purifier.row + 0.5f, corruption.cell.col + 0.5f, corruption.cell.row + 0.5f) <= purifier.effectRadius() * purifier.effectRadius()) {
                val purifierReduction = utilityPowerLevel(purifier) + if (purifier.imbuement == Imbuement.CONSERVATION) 1 else 0
                reduction = max(reduction, purifierReduction)
            }
        }
        return reduction
    }

    private fun corruptionUsesVial(corruption: CorruptedCell): Boolean = supplyCount(CraftedItem.PURIFIER_VIAL) > 0 && 4 - corruptionCleanseReduction(corruption) > 1

    private fun corruptionCleanseCost(corruption: CorruptedCell): Int {
        val vialReduction = if (corruptionUsesVial(corruption)) 2 else 0
        return max(1, 4 - corruptionCleanseReduction(corruption) - vialReduction)
    }

    private fun cleanseSelectedCorruption() {
        val corruption = selectedCorruption ?: return
        val useVial = corruptionUsesVial(corruption)
        val cost = corruptionCleanseCost(corruption)
        if (forgeCharges < cost) {
            setBanner("NEED $cost FORGE CHARGES TO CLEANSE", 1.7f)
            return
        }
        forgeCharges -= cost
        if (useVial) consumeSupply(CraftedItem.PURIFIER_VIAL)
        corruptions.remove(corruption)
        growthEssence = safeAdd(growthEssence, 1)
        selectedCorruption = null
        score = safeAdd(score, 250)
        burst(corruption.cell.col + 0.5f, corruption.cell.row + 0.5f, corruption.kind.accent, 20, 1.2f)
        setBanner("${corruption.kind.title.uppercase()} CLEANSED  +1 ESSENCE", 1.8f)
        saveRun()
    }

    private fun openEvolutionDraft(tower: Tower) {
        if (!tower.canEvolve() || evolutionCores <= 0) return
        evolutionTower = tower
        phase = GamePhase.EVOLUTION_DRAFT
        audio.play("build", 0.5f, 0.78f)
    }

    private fun chooseEvolution(index: Int) {
        val tower = evolutionTower ?: return
        val options = TowerEvolution.choices(tower.kind)
        if (index !in options.indices || evolutionCores <= 0 || !tower.canEvolve()) return
        tower.evolution = options[index]
        evolutionCores -= 1
        evolutionTower = null
        selectedTower = tower
        phase = GamePhase.BUILD
        tower.evolveFlash = 1f
        tower.evolveAura = 2.4f
        tower.evolveProof = 2.2f
        val evoTitle = tower.evolution!!.title.uppercase()
        setBanner("EVOLVED  $evoTitle", 2.6f)
        val cx = tower.col + 0.5f
        val cy = tower.row + 0.5f
        // E3: per-family confirm flavor (burst shape + SFX)
        when (tower.kind) {
            TowerKind.BOLT -> {
                burst(cx, cy, tower.kind.accent, 42, 2.0f)
                burst(cx, cy, Color.rgb(255, 255, 200), 16, 1.4f)
                audio.play("bolt", 0.55f, 1.25f)
                audio.play("build", 0.50f, 0.70f)
            }
            TowerKind.FROST -> {
                burst(cx, cy, tower.kind.accent, 28, 1.2f)
                burst(cx, cy, Color.rgb(200, 245, 255), 24, 0.9f)
                burst(cx, cy, Color.rgb(255, 255, 255), 12, 0.7f)
                audio.play("frost", 0.55f, 1.10f)
                audio.play("build", 0.45f, 0.85f)
            }
            TowerKind.CANNON -> {
                burst(cx, cy, tower.kind.accent, 48, 2.2f)
                burst(cx, cy, Color.rgb(255, 180, 90), 22, 1.5f)
                audio.play("cannon", 0.50f, 0.92f)
                audio.play("build", 0.60f, 0.55f)
            }
            TowerKind.EMBER -> {
                burst(cx, cy, tower.kind.accent, 40, 1.8f)
                burst(cx, cy, Color.rgb(255, 120, 40), 28, 1.3f)
                burst(cx, cy, Color.rgb(255, 220, 100), 14, 1.0f)
                audio.play("ember", 0.52f, 0.95f)
                audio.play("build", 0.55f, 0.60f)
            }
            TowerKind.BEACON -> {
                burst(cx, cy, tower.kind.accent, 32, 1.4f)
                burst(cx, cy, Color.rgb(230, 180, 255), 26, 1.1f)
                burst(cx, cy, Color.rgb(255, 215, 104), 14, 0.95f)
                audio.play("beacon", 0.55f, 1.15f)
                audio.play("build", 0.48f, 0.75f)
            }
            TowerKind.THORN -> {
                burst(cx, cy, tower.kind.accent, 36, 1.6f)
                burst(cx, cy, Color.rgb(180, 255, 120), 18, 1.1f)
                audio.play("bolt", 0.50f, 1.20f)
                audio.play("build", 0.50f, 0.72f)
            }
            TowerKind.LANCE -> {
                burst(cx, cy, tower.kind.accent, 40, 1.9f)
                burst(cx, cy, Color.rgb(200, 240, 255), 20, 1.2f)
                audio.play("bolt", 0.55f, 0.85f)
                audio.play("build", 0.55f, 0.65f)
            }
            TowerKind.MIRE -> {
                burst(cx, cy, tower.kind.accent, 34, 1.5f)
                burst(cx, cy, Color.rgb(60, 160, 130), 22, 1.0f)
                audio.play("frost", 0.50f, 0.75f)
                audio.play("build", 0.50f, 0.68f)
            }
            // 1.4-era towers: evolution confirmation burst in the tower's own accent
            // instead of throwing when a player evolves one.
            else -> {
                burst(cx, cy, tower.kind.accent, 40, 1.9f)
                burst(cx, cy, Color.rgb(255, 215, 104), 20, 1.3f)
                audio.play("build", 0.55f, 0.62f)
                audio.play("ui_click", 0.40f, 1.20f)
            }
        }
        floatingLabels.add(
            FloatingLabel(
                evoTitle,
                tower.col + 0.5f,
                tower.row + 0.15f,
                Color.rgb(255, 215, 104),
                life = 1.35f,
                pop = 1.55f
            )
        )
        audio.play("ui_click", 0.35f, 1.35f)
        saveRun()
    }

    private fun screenToCell(x: Float, y: Float): GridCell? {
        if (!inBoardViewport(x, y)) return null
        if (x < boardLeft || y < boardTop || x >= boardLeft + COLS * tileSize || y >= boardTop + ROWS * tileSize) return null
        val col = ((x - boardLeft) / tileSize).toInt()
        val row = ((y - boardTop) / tileSize).toInt()
        if (col !in 0 until COLS || row !in 0 until ROWS) return null
        return GridCell(col, row)
    }

    private fun isPathCell(cell: GridCell): Boolean = pathCells.contains(cell)

    private fun findTower(col: Int, row: Int): Tower? = towers.firstOrNull { it.col == col && it.row == row }

    private fun findTrap(col: Int, row: Int): SpikeTrap? = traps.firstOrNull { it.col == col && it.row == row }

    private fun findUtility(col: Int, row: Int): Utility? = utilities.firstOrNull { it.col == col && it.row == row }

    private fun findCorruption(col: Int, row: Int): CorruptedCell? = corruptions.firstOrNull { it.cell.col == col && it.cell.row == row }

    private fun drawFrame(canvas: Canvas) {
        if (phase == GamePhase.TITLE) {
            drawTitle(canvas)
            return
        }
        if (phase == GamePhase.CHALLENGE_MENU) {
            drawChallengeMenu(canvas)
            return
        }
        if (phase == GamePhase.WORKSHOP) {
            drawWorkshop(canvas)
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
        when (phase) {
            GamePhase.PAUSED -> drawPauseOverlay(canvas)
            GamePhase.VICTORY, GamePhase.GAME_OVER -> drawEndOverlay(canvas)
            GamePhase.PERK_DRAFT -> drawPerkDraft(canvas)
            GamePhase.EVOLUTION_DRAFT -> drawEvolutionDraft(canvas)
            else -> Unit
        }
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
        drawRoundedRect(canvas, titleChallengeRect.left, titleChallengeRect.top, titleChallengeRect.right, titleChallengeRect.bottom, dp(12f), Color.rgb(43, 57, 46))
        drawCenteredText(canvas, "SEEDED CHALLENGES", titleChallengeRect.centerX(), titleChallengeRect.centerY(), dp(11f), Color.rgb(224, 232, 226), true)
        drawCenteredText(canvas, "BEST $bestWave  •  DAILY $bestDailyWave  •  CUSTOM $bestCustomWave  •  $versionLabel", viewWidth * 0.5f, viewHeight - dp(12f), dp(9f), Color.rgb(113, 130, 119), true)
    }

    private fun drawChallengeMenu(canvas: Canvas) {
        canvas.drawColor(Color.rgb(10, 17, 13))
        drawTitleGrid(canvas)
        drawCenteredText(canvas, "OFFLINE SEEDED CHALLENGES", viewWidth * 0.5f, viewHeight * 0.13f, min(dp(35f), viewHeight * 0.075f), Color.WHITE, true, true)
        drawCenteredText(canvas, "WAVES, PERKS, ELITES, AND CORRUPTION REPEAT FOR THE SAME SEED", viewWidth * 0.5f, viewHeight * 0.20f, dp(10f), Color.rgb(160, 179, 166), true)
        val calendar = Calendar.getInstance()
        val dailySeed = calendar.get(Calendar.YEAR).toLong() * 10000L + (calendar.get(Calendar.MONTH) + 1).toLong() * 100L + calendar.get(Calendar.DAY_OF_MONTH).toLong()
        drawChallengeCard(canvas, challengeDailyRect, "DAILY PATH", "SEED $dailySeed", modifierForSeed(dailySeed), Color.rgb(190, 244, 78))
        var customSeed = 0L
        for (digit in challengeDigits) customSeed = customSeed * 10L + digit
        if (customSeed == 0L) customSeed = 1L
        drawChallengeCard(canvas, challengeSeedStartRect, "CUSTOM SEED", "TAP DIGITS • THEN TAP HERE", modifierForSeed(customSeed), Color.rgb(93, 220, 255))
        drawCenteredText(canvas, "SHAREABLE SEED", viewWidth * 0.5f, viewHeight * 0.59f, dp(10f), Color.rgb(147, 165, 153), true)
        seedDigitRects.forEachIndexed { index, rect ->
            drawRoundedRect(canvas, rect.left, rect.top, rect.right, rect.bottom, dp(9f), Color.rgb(28, 43, 34))
            drawCenteredText(canvas, challengeDigits[index].toString(), rect.centerX(), rect.centerY(), dp(18f), Color.WHITE, true)
        }
        drawCenteredText(canvas, "DAILY  W$bestDailyWave  ${formatNumber(bestDailyScore)}   •   CUSTOM  W$bestCustomWave  ${formatNumber(bestCustomScore)}", viewWidth * 0.5f, viewHeight * 0.76f, dp(10f), Color.rgb(190, 244, 78), true)
        drawRoundedRect(canvas, challengeBackRect.left, challengeBackRect.top, challengeBackRect.right, challengeBackRect.bottom, dp(12f), Color.rgb(43, 57, 46))
        drawCenteredText(canvas, "BACK", challengeBackRect.centerX(), challengeBackRect.centerY(), dp(11f), Color.WHITE, true)
    }

    private fun drawChallengeCard(canvas: Canvas, rect: RectF, title: String, subtitle: String, modifier: ChallengeModifier, accent: Int) {
        drawRoundedRect(canvas, rect.left, rect.top, rect.right, rect.bottom, dp(15f), Color.rgb(23, 37, 29))
        strokePaint.strokeWidth = dp(2f)
        strokePaint.color = accent
        canvas.drawRoundRect(rect, dp(15f), dp(15f), strokePaint)
        drawCenteredText(canvas, title, rect.centerX(), rect.top + rect.height() * 0.22f, dp(17f), accent, true, true)
        drawCenteredText(canvas, subtitle, rect.centerX(), rect.top + rect.height() * 0.41f, min(dp(9f), rect.width() * 0.035f), Color.rgb(178, 194, 183), true)
        drawCenteredText(canvas, modifier.title.uppercase(), rect.centerX(), rect.top + rect.height() * 0.62f, dp(12f), Color.WHITE, true)
        drawWrappedText(canvas, modifier.description, rect.centerX(), rect.top + rect.height() * 0.75f, rect.width() * 0.84f, dp(9f), Color.rgb(150, 169, 156), 2)
    }

    private fun drawPerkDraft(canvas: Canvas) {
        paint.color = Color.argb(232, 6, 12, 9)
        canvas.drawRect(0f, 0f, viewWidth, viewHeight, paint)
        drawCenteredText(canvas, "THE FORGE ANSWERS", viewWidth * 0.5f, viewHeight * 0.17f, min(dp(39f), viewHeight * 0.085f), Color.rgb(190, 244, 78), true, true)
        drawCenteredText(canvas, "CHOOSE ONE PERSISTENT RUN PERK  •  PICKS CAN STACK", viewWidth * 0.5f, viewHeight * 0.25f, dp(10f), Color.rgb(166, 183, 171), true)
        perkChoices.forEachIndexed { index, perk ->
            if (index >= perkRects.size) return@forEachIndexed
            val rect = perkRects[index]
            val accent = when (perk.category) {
                PerkCategory.TOWER -> Color.rgb(190, 244, 78)
                PerkCategory.TRAP -> Color.rgb(93, 220, 255)
                PerkCategory.ECONOMY -> Color.rgb(255, 203, 81)
                PerkCategory.CORE -> Color.rgb(255, 119, 104)
                PerkCategory.ROUTE -> Color.rgb(189, 136, 255)
            }
            drawRoundedRect(canvas, rect.left, rect.top, rect.right, rect.bottom, dp(15f), Color.rgb(23, 37, 29))
            strokePaint.strokeWidth = dp(2f)
            strokePaint.color = accent
            canvas.drawRoundRect(rect, dp(15f), dp(15f), strokePaint)
            drawCenteredText(canvas, perk.category.name, rect.centerX(), rect.top + rect.height() * 0.14f, dp(8f), accent, true)
            drawWrappedText(canvas, perk.title.uppercase(), rect.centerX(), rect.top + rect.height() * 0.34f, rect.width() * 0.82f, dp(14f), Color.WHITE, 2, true)
            drawWrappedText(canvas, perk.description, rect.centerX(), rect.top + rect.height() * 0.62f, rect.width() * 0.82f, dp(9f), Color.rgb(169, 187, 174), 3)
            drawCenteredText(canvas, "STACK ${perkCount(perk) + 1}", rect.centerX(), rect.bottom - rect.height() * 0.12f, dp(9f), accent, true)
        }
    }

    private fun drawEvolutionDraft(canvas: Canvas) {
        val tower = evolutionTower ?: return
        paint.color = Color.argb(232, 6, 12, 9)
        canvas.drawRect(0f, 0f, viewWidth, viewHeight, paint)
        drawCenteredText(canvas, "EVOLUTION CORE", viewWidth * 0.5f, viewHeight * 0.17f, min(dp(39f), viewHeight * 0.085f), Color.rgb(255, 203, 81), true, true)
        drawCenteredText(canvas, "${tower.kind.title.uppercase()}  •  CHOOSE ONE MUTUALLY EXCLUSIVE FORM", viewWidth * 0.5f, viewHeight * 0.25f, dp(10f), Color.rgb(177, 192, 181), true)
        val options = TowerEvolution.choices(tower.kind)
        options.forEachIndexed { index, evolution ->
            if (index >= evolutionRects.size) return@forEachIndexed
            val rect = evolutionRects[index]
            drawRoundedRect(canvas, rect.left, rect.top, rect.right, rect.bottom, dp(15f), Color.rgb(24, 38, 30))
            strokePaint.strokeWidth = dp(2f)
            strokePaint.color = tower.kind.accent
            canvas.drawRoundRect(rect, dp(15f), dp(15f), strokePaint)
            drawWrappedText(canvas, evolution.title.uppercase(), rect.centerX(), rect.top + rect.height() * 0.28f, rect.width() * 0.84f, dp(17f), Color.WHITE, 2, true)
            drawWrappedText(canvas, evolution.description, rect.centerX(), rect.top + rect.height() * 0.58f, rect.width() * 0.82f, dp(10f), Color.rgb(169, 187, 174), 3)
            drawCenteredText(canvas, "SPEND 1 CORE", rect.centerX(), rect.bottom - rect.height() * 0.12f, dp(9f), Color.rgb(255, 203, 81), true)
        }
    }

    private fun drawWorkshop(canvas: Canvas) {
        canvas.drawColor(Color.rgb(9, 15, 12))
        drawTitleGrid(canvas)
        drawRoundedRect(canvas, workshopBackRect.left, workshopBackRect.top, workshopBackRect.right, workshopBackRect.bottom, dp(10f), Color.rgb(35, 49, 39))
        drawCenteredText(canvas, "BACK", workshopBackRect.centerX(), workshopBackRect.centerY(), dp(10f), Color.WHITE, true)
        drawCenteredText(canvas, "FORGEWORKS", viewWidth * 0.5f, viewHeight * 0.075f, min(dp(31f), viewHeight * 0.067f), Color.rgb(255, 183, 105), true, true)
        drawCenteredText(canvas, "WORKSHOP L${workshopLevel()}  •  $gold BLOCKS  •  $salvageParts PARTS  •  $growthEssence ESSENCE", viewWidth * 0.5f, viewHeight * 0.115f, dp(9f), Color.rgb(184, 199, 188), true)
        for ((tab, rect) in workshopTabRects) {
            val active = workshopTab == tab
            drawRoundedRect(canvas, rect.left, rect.top, rect.right, rect.bottom, dp(10f), if (active) Color.rgb(255, 183, 105) else Color.rgb(30, 44, 35))
            drawCenteredText(canvas, tab.name, rect.centerX(), rect.centerY(), dp(10f), if (active) Color.rgb(26, 20, 13) else Color.WHITE, true)
        }
        val start = workshopPageIndex * 4
        when (workshopTab) {
            WorkshopTab.CRAFT -> CraftedItem.values().drop(start).take(4).forEachIndexed { local, item -> drawCraftCard(canvas, workshopCardRects[local], item) }
            WorkshopTab.SUPPLIES -> CraftedItem.values().drop(start).take(4).forEachIndexed { local, item -> drawSupplyCard(canvas, workshopCardRects[local], item) }
            WorkshopTab.IMBUE -> {
                val targetName = imbuementTower?.kind?.title ?: imbuementTrap?.kind?.title ?: imbuementUtility?.kind?.title
                val currentImbuement = imbuementTower?.imbuement ?: imbuementTrap?.imbuement ?: imbuementUtility?.imbuement
                if (targetName == null) {
                    drawCenteredText(canvas, "SELECT A LEVEL 3 STRUCTURE ON THE BOARD, THEN TAP IMBUE", viewWidth * 0.5f, viewHeight * 0.50f, dp(12f), Color.rgb(178, 194, 183), true)
                } else {
                    drawCenteredText(canvas, "TARGET ${targetName.uppercase()} • 120 BLOCKS + 1 ESSENCE + 1 SIGIL${if (currentImbuement != null) " • REPLACES ${currentImbuement.title.uppercase()}" else ""}", viewWidth * 0.5f, viewHeight * 0.265f, dp(9f), Color.rgb(213, 182, 255), true)
                    Imbuement.values().drop(start).take(4).forEachIndexed { local, imbuement -> drawImbuementCard(canvas, workshopCardRects[local], imbuement) }
                }
            }
        }
        val totalEntries = if (workshopTab == WorkshopTab.IMBUE) Imbuement.values().size else CraftedItem.values().size
        val pages = max(1, (totalEntries + 3) / 4)
        drawRoundedRect(canvas, workshopPreviousRect.left, workshopPreviousRect.top, workshopPreviousRect.right, workshopPreviousRect.bottom, dp(10f), Color.rgb(31, 45, 36))
        drawCenteredText(canvas, "PREVIOUS", workshopPreviousRect.centerX(), workshopPreviousRect.centerY(), dp(9f), Color.WHITE, true)
        drawRoundedRect(canvas, workshopNextRect.left, workshopNextRect.top, workshopNextRect.right, workshopNextRect.bottom, dp(10f), Color.rgb(31, 45, 36))
        drawCenteredText(canvas, "NEXT  ${workshopPageIndex + 1}/$pages", workshopNextRect.centerX(), workshopNextRect.centerY(), dp(9f), Color.WHITE, true)
        if (bannerTimer > 0f) drawCenteredText(canvas, bannerText, viewWidth * 0.5f, viewHeight * 0.94f, dp(10f), Color.rgb(190, 244, 78), true)
    }

    private fun drawCraftCard(canvas: Canvas, rect: RectF, item: CraftedItem) {
        val unlocked = workshopLevel() >= item.workshopLevel
        val blockCost = craftedBlockCost(item)
        val affordable = unlocked && gold >= blockCost && salvageParts >= item.partCost && growthEssence >= item.essenceCost && supplyCount(item) < item.maxStack
        drawRoundedRect(canvas, rect.left, rect.top, rect.right, rect.bottom, dp(12f), if (affordable) Color.rgb(30, 48, 37) else Color.rgb(27, 35, 30))
        strokePaint.strokeWidth = dp(1.5f)
        strokePaint.color = if (affordable) Color.rgb(255, 183, 105) else Color.rgb(65, 77, 68)
        canvas.drawRoundRect(rect, dp(12f), dp(12f), strokePaint)
        drawBitmapCentered(canvas, sprites.craftedItem(item), rect.left + rect.height() * 0.22f, rect.top + rect.height() * 0.22f, rect.height() * 0.31f)
        drawCenteredText(canvas, item.title.uppercase(), rect.centerX(), rect.top + rect.height() * 0.19f, dp(11f), if (unlocked) Color.WHITE else Color.rgb(105, 119, 109), true)
        drawWrappedText(canvas, item.description, rect.centerX(), rect.top + rect.height() * 0.47f, rect.width() * 0.86f, dp(8f), Color.rgb(165, 182, 170), 2)
        drawCenteredText(canvas, "$blockCost B  •  ${item.partCost} P  •  ${item.essenceCost} E", rect.centerX(), rect.top + rect.height() * 0.72f, dp(8f), if (affordable) Color.rgb(190, 244, 78) else Color.rgb(255, 126, 110), true)
        drawCenteredText(canvas, if (unlocked) "CRAFT  ${supplyCount(item)}/${item.maxStack}" else "WORKSHOP LEVEL ${item.workshopLevel}", rect.centerX(), rect.top + rect.height() * 0.88f, dp(8f), Color.rgb(255, 183, 105), true)
    }

    private fun drawSupplyCard(canvas: Canvas, rect: RectF, item: CraftedItem) {
        val count = supplyCount(item)
        drawRoundedRect(canvas, rect.left, rect.top, rect.right, rect.bottom, dp(12f), if (count > 0) Color.rgb(30, 48, 37) else Color.rgb(25, 34, 29))
        spritePaint.alpha = if (count > 0) 255 else 90
        drawBitmapCentered(canvas, sprites.craftedItem(item), rect.left + rect.height() * 0.22f, rect.top + rect.height() * 0.23f, rect.height() * 0.31f)
        spritePaint.alpha = 255
        drawCenteredText(canvas, item.title.uppercase(), rect.centerX(), rect.top + rect.height() * 0.22f, dp(11f), if (count > 0) Color.WHITE else Color.rgb(104, 119, 109), true)
        drawWrappedText(canvas, item.description, rect.centerX(), rect.top + rect.height() * 0.50f, rect.width() * 0.86f, dp(8f), Color.rgb(165, 182, 170), 2)
        val automatic = item == CraftedItem.RECOVERY_WRAP || item == CraftedItem.PURIFIER_VIAL || item == CraftedItem.REFORGE_COUPLER || item == CraftedItem.UTILITY_GEARSET
        drawCenteredText(canvas, "OWNED $count  •  ${if (automatic) "AUTO" else "TAP TO USE"}", rect.centerX(), rect.top + rect.height() * 0.82f, dp(8f), if (count > 0) Color.rgb(190, 244, 78) else Color.rgb(112, 126, 116), true)
    }

    private fun drawImbuementCard(canvas: Canvas, rect: RectF, imbuement: Imbuement) {
        val compatible = imbuementCompatible(imbuement)
        drawRoundedRect(canvas, rect.left, rect.top, rect.right, rect.bottom, dp(12f), if (compatible) Color.rgb(30, 41, 35) else Color.rgb(25, 30, 27))
        strokePaint.strokeWidth = dp(2f)
        strokePaint.color = if (compatible) imbuement.accent else Color.rgb(64, 72, 67)
        canvas.drawRoundRect(rect, dp(12f), dp(12f), strokePaint)
        spritePaint.alpha = if (compatible) 255 else 70
        drawBitmapCentered(canvas, sprites.imbuement(imbuement), rect.left + rect.height() * 0.22f, rect.top + rect.height() * 0.25f, rect.height() * 0.34f)
        spritePaint.alpha = 255
        drawCenteredText(canvas, imbuement.title.uppercase(), rect.centerX(), rect.top + rect.height() * 0.24f, dp(12f), if (compatible) imbuement.accent else Color.rgb(105, 116, 109), true)
        drawWrappedText(canvas, imbuement.description, rect.centerX(), rect.top + rect.height() * 0.55f, rect.width() * 0.86f, dp(8f), if (compatible) Color.rgb(179, 194, 183) else Color.rgb(103, 114, 107), 3)
        drawCenteredText(canvas, if (compatible) "BIND SIGIL" else "NO EFFECT ON TARGET", rect.centerX(), rect.top + rect.height() * 0.84f, dp(8f), if (compatible) Color.WHITE else Color.rgb(126, 137, 130), true)
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
        // F0: clip world draw to board viewport so chrome stays screen-fixed
        canvas.save()
        canvas.clipRect(viewportLeft, viewportTop, viewportRight, viewportBottom)
        canvas.drawRoundRect(boardLeft - dp(6f), boardTop - dp(6f), boardLeft + COLS * tileSize + dp(6f), boardTop + ROWS * tileSize + dp(6f), dp(12f), dp(12f), paint)
        val col0 = max(0, ((viewportLeft - boardLeft) / tileSize).toInt() - 1)
        val row0 = max(0, ((viewportTop - boardTop) / tileSize).toInt() - 1)
        val col1 = min(COLS - 1, ((viewportRight - boardLeft) / tileSize).toInt() + 1)
        val row1 = min(ROWS - 1, ((viewportBottom - boardTop) / tileSize).toInt() + 1)
        for (row in row0..row1) for (col in col0..col1) drawTerrainTile(canvas, col, row)

        if (pathCells.size > 1) {
            strokePaint.style = Paint.Style.STROKE
            strokePaint.strokeCap = Paint.Cap.ROUND
            // Soft base route
            strokePaint.strokeWidth = max(2f, tileSize * 0.055f)
            strokePaint.color = Color.argb(110, 255, 241, 195)
            val route = Path()
            route.moveTo(cellCenterX(pathCells[0].col), cellCenterY(pathCells[0].row))
            for (i in 1 until pathCells.size) route.lineTo(cellCenterX(pathCells[i].col), cellCenterY(pathCells[i].row))
            canvas.drawPath(route, strokePaint)
            // Traveling shimmer dashes along the path (1.3 Phase C — quiet ambient)
            val shimmerPhase = ambientTime * 0.55f
            val segmentCount = pathCells.size - 1
            for (i in 0 until segmentCount) {
                val local = ((shimmerPhase + i * 0.17f) % 1.4f)
                if (local > 1f) continue
                val t = local
                val a = pathCells[i]
                val b = pathCells[i + 1]
                val x1 = cellCenterX(a.col)
                val y1 = cellCenterY(a.row)
                val x2 = cellCenterX(b.col)
                val y2 = cellCenterY(b.row)
                val sx = x1 + (x2 - x1) * t
                val sy = y1 + (y2 - y1) * t
                val fade = if (t < 0.15f) t / 0.15f else if (t > 0.85f) (1f - t) / 0.15f else 1f
                paint.color = Color.argb((28 * fade).toInt().coerceIn(0, 255), 255, 250, 210)
                canvas.drawCircle(sx, sy, tileSize * 0.07f, paint)
            }
        }

        drawDefenseSynergies(canvas)
        for (corruption in corruptions) drawCorruption(canvas, corruption, corruption === selectedCorruption)
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

        val chosenUtility = selectedUtility
        if (chosenUtility != null && (chosenUtility.kind == UtilityKind.PURIFIER_TOTEM || chosenUtility.kind == UtilityKind.REFORGE_ANCHOR || chosenUtility.kind == UtilityKind.SURVEYOR_STATION)) {
            paint.color = Color.argb(30, Color.red(chosenUtility.kind.accent), Color.green(chosenUtility.kind.accent), Color.blue(chosenUtility.kind.accent))
            canvas.drawCircle(cellCenterX(chosenUtility.col), cellCenterY(chosenUtility.row), chosenUtility.effectRadius() * tileSize, paint)
        }
        for (tower in towers) drawTower(canvas, tower, tower === chosenTower)
        for (utility in utilities) drawUtility(canvas, utility, utility === chosenUtility)
        for (enemy in enemies) drawEnemy(canvas, enemy)
        for (projectile in projectiles) drawProjectile(canvas, projectile)
        for (fx in impactEffects) drawImpact(canvas, fx)
        for (particle in particles) drawParticle(canvas, particle)
        for (label in floatingLabels) {
            val lifeRatio = (label.life / max(0.05f, label.maxLife)).coerceIn(0f, 1f)
            val alpha = (min(1f, label.life * 2.2f) * 255f).toInt().coerceIn(0, 255)
            val labelColor = Color.argb(alpha, Color.red(label.color), Color.green(label.color), Color.blue(label.color))
            // Pop scale: overshoot at spawn, settle, then slight shrink on fade
            val spawnT = 1f - lifeRatio
            val popScale = when {
                spawnT < 0.18f -> 0.72f + (spawnT / 0.18f) * (label.pop * 1.18f - 0.72f)
                spawnT < 0.32f -> label.pop * (1.18f - (spawnT - 0.18f) / 0.14f * 0.18f)
                else -> label.pop * (1f - (1f - lifeRatio) * 0.12f)
            }
            val textSize = max(dp(9f), tileSize * 0.20f) * popScale
            if (label.pop > 1.05f) {
                paint.color = Color.argb((alpha * 0.22f).toInt().coerceIn(0, 255), Color.red(label.color), Color.green(label.color), Color.blue(label.color))
                canvas.drawCircle(gridX(label.x), gridY(label.y), textSize * 0.85f, paint)
            }
            drawCenteredText(canvas, label.message, gridX(label.x), gridY(label.y), textSize, labelColor, true)
        }
        // Balance the single F0 viewport-clip save taken at the top of drawBoard: exactly one
        // restore per frame. Keep this the last statement and keep drawBoard free of early
        // returns so the save stack can never drift.
        canvas.restore() // F0 viewport clip
    }

    private fun drawCorruption(canvas: Canvas, corruption: CorruptedCell, selected: Boolean) {
        val x = cellCenterX(corruption.cell.col)
        val y = cellCenterY(corruption.cell.row)
        val pulse = 0.72f + sin(ambientTime * 2.8f + corruption.id) * 0.12f
        paint.color = Color.argb(80, Color.red(corruption.kind.accent), Color.green(corruption.kind.accent), Color.blue(corruption.kind.accent))
        canvas.drawCircle(x, y, tileSize * 0.44f, paint)
        strokePaint.strokeWidth = tileSize * if (selected) 0.07f else 0.035f
        strokePaint.color = if (selected) Color.WHITE else corruption.kind.accent
        canvas.drawCircle(x, y, tileSize * (0.28f + pulse * 0.08f), strokePaint)
        drawBitmapCentered(canvas, sprites.corruption(corruption.kind), x, y, tileSize * 0.76f)
    }

    private fun drawDefenseSynergies(canvas: Canvas) {
        for (tower in towers) {
            for (other in towers) {
                if (tower === other || tower.kind.ordinal >= other.kind.ordinal || distanceSquared(tower.col.toFloat(), tower.row.toFloat(), other.col.toFloat(), other.row.toFloat()) > 6.25f) continue
                val name = when {
                    (tower.kind == TowerKind.BOLT && other.kind == TowerKind.BEACON) || (tower.kind == TowerKind.BEACON && other.kind == TowerKind.BOLT) -> "RESONANT VOLLEY"
                    (tower.kind == TowerKind.FROST && other.kind == TowerKind.EMBER) || (tower.kind == TowerKind.EMBER && other.kind == TowerKind.FROST) -> "THERMAL SHOCK"
                    else -> null
                }
                if (name != null) drawSynergyLink(canvas, tower.col, tower.row, other.col, other.row, name, tower === selectedTower || other === selectedTower)
            }
            for (trap in traps) {
                if (distanceSquared(tower.col.toFloat(), tower.row.toFloat(), trap.col.toFloat(), trap.row.toFloat()) > 6.25f) continue
                val name = when {
                    tower.kind == TowerKind.CANNON && trap.kind == TrapKind.ARC -> "THUNDERBURST"
                    tower.kind == TowerKind.FROST && trap.kind == TrapKind.CRUSHER -> "SHATTERFIELD"
                    tower.kind == TowerKind.EMBER && trap.kind == TrapKind.SPIKE -> "MOLTEN SPIKES"
                    tower.kind == TowerKind.BOLT && trap.kind == TrapKind.ROOT -> "VERDANT VOLT"
                    tower.kind == TowerKind.EMBER && trap.kind == TrapKind.ROOT -> "WILDFIRE ROOTS"
                    tower.kind == TowerKind.BEACON && trap.kind == TrapKind.ARC -> "STORM NETWORK"
                    tower.kind == TowerKind.CANNON && trap.kind == TrapKind.CRUSHER -> "SEISMIC RESET"
                    tower.kind == TowerKind.BEACON -> "BEACON RELAY"
                    else -> null
                }
                if (name != null) drawSynergyLink(canvas, tower.col, tower.row, trap.col, trap.row, name, tower === selectedTower || trap === selectedTrap)
            }
        }
    }

    private fun drawSynergyLink(canvas: Canvas, firstCol: Int, firstRow: Int, secondCol: Int, secondRow: Int, name: String, showLabel: Boolean) {
        val x1 = cellCenterX(firstCol)
        val y1 = cellCenterY(firstRow)
        val x2 = cellCenterX(secondCol)
        val y2 = cellCenterY(secondRow)
        strokePaint.strokeWidth = max(1f, tileSize * 0.025f)
        strokePaint.color = Color.argb(if (showLabel) 205 else 65, 190, 244, 78)
        canvas.drawLine(x1, y1, x2, y2, strokePaint)
        if (showLabel) {
            val x = (x1 + x2) * 0.5f
            val y = (y1 + y2) * 0.5f
            val width = min(tileSize * 2.8f, paint.measureText(name) + dp(10f))
            drawRoundedRect(canvas, x - width * 0.5f, y - dp(9f), x + width * 0.5f, y + dp(9f), dp(7f), Color.argb(220, 15, 28, 20))
            drawCenteredText(canvas, name, x, y, max(dp(6f), tileSize * 0.10f), Color.rgb(190, 244, 78), true)
        }
    }

    private fun drawTerrainTile(canvas: Canvas, col: Int, row: Int) {
        val left = boardLeft + col * tileSize
        val top = boardTop + row * tileSize
        val destination = RectF(left, top, left + tileSize, top + tileSize)
        val pathTile = isPathCell(GridCell(col, row))
        if (pathTile) {
            spritePaint.alpha = 255
            canvas.drawBitmap(sprites.path, null, destination, spritePaint)
            val sheen = 0.5f + 0.5f * sin(ambientTime * 1.6f + col * 0.55f + row * 0.35f)
            // E2: path cells near a freshly evolved tower glow warmer
            var auraBoost = 0f
            for (tower in towers) {
                if (tower.evolveAura <= 0.02f) continue
                val d2 = (tower.col - col) * (tower.col - col) + (tower.row - row) * (tower.row - row)
                if (d2 <= 2) { // self + orthogonal neighbors (and diagonal at d2=2)
                    auraBoost = max(auraBoost, tower.evolveAura / 2.4f * (1f - d2 * 0.28f))
                }
            }
            val sheenAlpha = (10 + sheen * 14 + auraBoost * 55).toInt().coerceIn(0, 255)
            paint.color = Color.argb(sheenAlpha, 255, 248, 210)
            canvas.drawRect(
                left + tileSize * 0.12f,
                top + tileSize * 0.18f,
                left + tileSize * 0.88f,
                top + tileSize * 0.28f,
                paint
            )
            if (auraBoost > 0.08f) {
                paint.color = Color.argb((auraBoost * 40).toInt().coerceIn(0, 255), 255, 215, 104)
                canvas.drawCircle(left + tileSize * 0.5f, top + tileSize * 0.5f, tileSize * (0.12f + auraBoost * 0.08f), paint)
            }
        } else {
            val sway = 0.5f + 0.5f * sin(ambientTime * 1.35f + col * 0.7f + row * 0.9f)
            spritePaint.alpha = (235 + (sway * 20f).toInt()).coerceIn(220, 255)
            canvas.drawBitmap(sprites.grass, null, destination, spritePaint)
            spritePaint.alpha = 255
            if ((col * 41 + row * 67) % 5 == 0) {
                val tip = sway * tileSize * 0.02f
                paint.color = Color.argb((40 + sway * 18).toInt().coerceIn(0, 255), 20, 83, 35)
                canvas.drawCircle(left + tileSize * 0.23f, top + tileSize * 0.70f - tip, tileSize * 0.07f, paint)
            }
            if ((col * 17 + row * 29) % 7 == 0) {
                val tip = sin(ambientTime * 1.35f + col * 0.7f + row * 0.9f) * tileSize * 0.015f
                paint.color = Color.argb((8 + sway * 10).toInt().coerceIn(0, 255), 90, 160, 70)
                canvas.drawCircle(left + tileSize * 0.72f, top + tileSize * 0.28f + tip, tileSize * 0.05f, paint)
            }
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
        val gateStep = floor(ambientTime * 2.0f).toInt() % 4
        val gateFrame = when (gateStep) { 0 -> 0; 1 -> 1; 2 -> 2; else -> 1 }
        paint.color = Color.argb((30 + pulse * 25).toInt().coerceIn(0, 255), 190, 244, 78)
        canvas.drawCircle(x, y, tileSize * (0.42f + pulse * 0.04f), paint)
        drawSpriteFrameCentered(canvas, sprites.gatePart, gateFrame, x, y, tileSize * 0.90f)
        strokePaint.strokeWidth = tileSize * 0.045f
        strokePaint.color = Color.argb(190, 190, 244, 78)
        canvas.drawCircle(x, y, tileSize * 0.25f * pulse, strokePaint)
        drawCenteredText(canvas, "IN", x, y, max(dp(7f), tileSize * 0.14f), Color.rgb(12, 28, 18), true)
    }

    private fun drawCore(canvas: Canvas) {
        val x = cellCenterX(COLS - 1)
        val y = cellCenterY(START_ROW)
        val healthRatio = if (maxCore > 0) lives.toFloat() / maxCore.toFloat() else 1f
        val beatHz = if (healthRatio < 0.35f) 5.2f else if (healthRatio < 0.6f) 4.2f else 3.2f
        val pulse = 0.5f + 0.5f * sin(ambientTime * beatHz)
        val secondary = 0.5f + 0.5f * sin(ambientTime * beatHz * 0.5f + 1.1f)
        val danger = (1f - healthRatio).coerceIn(0f, 1f)
        val glowR = (190 + danger * 55).toInt().coerceIn(0, 255)
        val glowG = (244 - danger * 140).toInt().coerceIn(0, 255)
        val glowB = (78 - danger * 20).toInt().coerceIn(0, 255)
        paint.color = Color.argb((28 + pulse * 40 + danger * 20).toInt().coerceIn(0, 255), glowR, glowG, glowB)
        canvas.drawCircle(x, y, tileSize * (0.52f + pulse * 0.07f + secondary * 0.02f), paint)
        paint.color = Color.argb((45 + pulse * 55).toInt().coerceIn(0, 255), glowR, glowG, glowB)
        canvas.drawCircle(x, y, tileSize * (0.47f + pulse * 0.05f), paint)
        val coreStep = floor(ambientTime * 2.5f).toInt() % 4
        val coreFrame = when (coreStep) { 0 -> 0; 1 -> 1; 2 -> 2; else -> 1 }
        drawSpriteFrameCentered(canvas, sprites.corePart, coreFrame, x, y, tileSize * (0.94f + pulse * 0.04f))
        paint.color = Color.argb(
            255,
            (224 + danger * 30).toInt().coerceIn(0, 255),
            (255 - danger * 80).toInt().coerceIn(0, 255),
            (169 - danger * 40).toInt().coerceIn(0, 255)
        )
        canvas.drawCircle(x, y, tileSize * (0.09f + pulse * 0.02f), paint)
        if (pulse > 0.92f) {
            strokePaint.style = Paint.Style.STROKE
            strokePaint.strokeWidth = max(1.5f, tileSize * 0.03f)
            strokePaint.color = Color.argb((50 + danger * 40).toInt().coerceIn(0, 255), glowR, glowG, glowB)
            canvas.drawCircle(x, y, tileSize * (0.58f + (pulse - 0.92f) * 1.2f), strokePaint)
        }
    }

    private fun drawTrap(canvas: Canvas, trap: SpikeTrap, selected: Boolean) {
        val x = cellCenterX(trap.col)
        val y = cellCenterY(trap.row)
        val scale = tileSize * (0.74f + trap.pulse * 0.10f)
        val strip = sprites.trap(trap.kind)
        val frame = when {
            strip.frameCount == 1 -> 0
            trap.pulse > 0.70f -> 2
            trap.pulse > 0.20f -> 1
            else -> 0
        }
        if (selected) {
            strokePaint.strokeWidth = tileSize * 0.05f
            strokePaint.color = Color.WHITE
            canvas.drawCircle(x, y, tileSize * 0.39f, strokePaint)
        }
        drawSpriteFrameCentered(canvas, strip, frame, x, y, scale)
        if (trap.kind == TrapKind.ARC) {
            strokePaint.strokeWidth = tileSize * 0.035f
            strokePaint.color = trap.kind.accent
            canvas.drawCircle(x, y, tileSize * (0.18f + trap.pulse * 0.12f), strokePaint)
        }
        drawRankDots(canvas, x, y + tileSize * 0.34f, trap.level, trap.overcharge, trap.kind.accent)
        drawImbuementGlyph(canvas, x, y, trap.imbuement)
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
        // E1/E3: evolution confirm flash — family-tinted rim expanding from the cell
        val evoFlash = tower.evolveFlash
        if (evoFlash > 0.02f) {
            val flashT = 1f - evoFlash
            val accent = tower.kind.accent
            // start color = family accent; end color = warm gold
            val rimR = (Color.red(accent) + (255 - Color.red(accent)) * flashT).toInt().coerceIn(0, 255)
            val rimG = (Color.green(accent) + (215 - Color.green(accent)) * flashT).toInt().coerceIn(0, 255)
            val rimB = (Color.blue(accent) + (104 - Color.blue(accent)) * flashT).toInt().coerceIn(0, 255)
            val rimThick = when (tower.kind) {
                TowerKind.BOLT -> 0.06f + evoFlash * 0.05f   // snap thin
                TowerKind.FROST -> 0.07f + evoFlash * 0.05f  // ice rim
                TowerKind.CANNON -> 0.10f + evoFlash * 0.08f // heavy
                TowerKind.EMBER -> 0.09f + evoFlash * 0.07f  // coal bloom
                TowerKind.BEACON -> 0.07f + evoFlash * 0.06f // resonance
                TowerKind.THORN -> 0.07f + evoFlash * 0.05f
                TowerKind.LANCE -> 0.06f + evoFlash * 0.05f
                TowerKind.MIRE -> 0.08f + evoFlash * 0.06f
                else -> 0.08f + evoFlash * 0.06f
            }
            val rimExpand = when (tower.kind) {
                TowerKind.BOLT -> 0.28f
                TowerKind.FROST -> 0.24f
                TowerKind.CANNON -> 0.20f
                TowerKind.EMBER -> 0.26f
                TowerKind.BEACON -> 0.30f
                TowerKind.THORN -> 0.27f
                TowerKind.LANCE -> 0.29f
                TowerKind.MIRE -> 0.25f
                else -> 0.26f
            }
            strokePaint.style = Paint.Style.STROKE
            strokePaint.strokeWidth = tileSize * rimThick
            strokePaint.color = Color.argb((220 * evoFlash).toInt().coerceIn(0, 255), rimR, rimG, rimB)
            canvas.drawCircle(x, y, tileSize * (0.36f + (1f - evoFlash) * rimExpand), strokePaint)
            // fill wash
            val fillA = when (tower.kind) {
                TowerKind.EMBER -> 70
                TowerKind.FROST -> 45
                TowerKind.CANNON -> 60
                else -> 55
            }
            paint.color = Color.argb((fillA * evoFlash).toInt().coerceIn(0, 255), rimR, rimG, rimB)
            canvas.drawCircle(x, y, tileSize * (0.40f + evoFlash * 0.08f), paint)
            // E3 secondary ring: frost ice / beacon resonance / bolt snap spark
            when (tower.kind) {
                TowerKind.FROST -> {
                    strokePaint.strokeWidth = tileSize * 0.035f
                    strokePaint.color = Color.argb((160 * evoFlash).toInt().coerceIn(0, 255), 220, 250, 255)
                    canvas.drawCircle(x, y, tileSize * (0.30f + (1f - evoFlash) * 0.18f), strokePaint)
                }
                TowerKind.BEACON -> {
                    strokePaint.strokeWidth = tileSize * 0.028f
                    strokePaint.color = Color.argb((140 * evoFlash).toInt().coerceIn(0, 255), 210, 160, 255)
                    canvas.drawCircle(x, y, tileSize * (0.50f + (1f - evoFlash) * 0.12f), strokePaint)
                }
                TowerKind.BOLT -> {
                    // snap cross sparks
                    paint.color = Color.argb((180 * evoFlash).toInt().coerceIn(0, 255), 255, 255, 210)
                    val s = tileSize * (0.18f + (1f - evoFlash) * 0.15f)
                    canvas.drawCircle(x + s, y, tileSize * 0.05f, paint)
                    canvas.drawCircle(x - s, y, tileSize * 0.05f, paint)
                    canvas.drawCircle(x, y + s, tileSize * 0.05f, paint)
                    canvas.drawCircle(x, y - s, tileSize * 0.05f, paint)
                }
                TowerKind.EMBER -> {
                    paint.color = Color.argb((90 * evoFlash).toInt().coerceIn(0, 255), 255, 90, 40)
                    canvas.drawCircle(x, y, tileSize * (0.22f + evoFlash * 0.12f), paint)
                }
                TowerKind.CANNON -> {
                    strokePaint.strokeWidth = tileSize * 0.05f
                    strokePaint.color = Color.argb((120 * evoFlash).toInt().coerceIn(0, 255), 255, 140, 60)
                    canvas.drawCircle(x, y, tileSize * (0.28f + evoFlash * 0.06f), strokePaint)
                }
                // 1.4-era towers evolve with the accent ring only; no secondary ring
                // (an empty branch is fine here — this when is statement context).
                else -> {}
            }
        }
        val base = when (tower.kind) {
            TowerKind.BOLT -> sprites.towerBase
            TowerKind.FROST -> sprites.frostBase
            TowerKind.CANNON -> sprites.cannonBase
            TowerKind.EMBER -> sprites.emberBase
            TowerKind.BEACON -> sprites.beaconBase
            TowerKind.THORN -> sprites.thornBase
            TowerKind.LANCE -> sprites.lanceBase
            TowerKind.MIRE -> sprites.mireBase
            TowerKind.GALE -> sprites.galeBase
            TowerKind.SUNFORGE -> sprites.sunforgeBase
            TowerKind.LODESTONE -> sprites.lodestoneBase
            TowerKind.HOWL -> sprites.howlBase
            TowerKind.VITRIOL -> sprites.vitriolBase
            TowerKind.GRAVEBOLT -> sprites.graveboltBase
            TowerKind.AEGIS_LOOM -> sprites.aegisLoomBase
        }
        drawBitmapCentered(canvas, base, x, y + tileSize * 0.05f, tileSize * 0.88f)
        val firingFrame = when {
            tower.recoil > 0.70f -> 2
            tower.recoil > 0.20f -> 1
            else -> 0
        }
        when (tower.kind) {
            TowerKind.BOLT -> drawSpriteFrameCentered(canvas, sprites.greenTurret, firingFrame, x, y, tileSize * 0.86f, tower.angle * 57.29578f)
            TowerKind.FROST -> {
                drawSpriteFrameCentered(canvas, sprites.paleTurret, firingFrame, x, y, tileSize * 0.82f, tower.angle * 57.29578f)
                paint.color = Color.argb(80, 93, 220, 255)
                canvas.drawCircle(x, y, tileSize * 0.21f, paint)
            }
            TowerKind.CANNON -> drawSpriteFrameCentered(canvas, sprites.cannonTurret, firingFrame, x, y - tileSize * 0.04f, tileSize * 0.82f, tower.angle * 57.29578f)
            TowerKind.EMBER -> drawSpriteFrameCentered(canvas, sprites.emberFlame, firingFrame, x, y - tileSize * 0.10f, tileSize * (0.70f + sin(ambientTime * 6f) * 0.03f))
            TowerKind.BEACON -> drawSpriteFrameCentered(canvas, sprites.beaconPulse, firingFrame, x, y - tileSize * 0.03f, tileSize * (0.70f + sin(ambientTime * 4f) * 0.05f), ambientTime * 30f)
            TowerKind.THORN -> drawSpriteFrameCentered(canvas, sprites.thornTurret, firingFrame, x, y, tileSize * 0.84f, tower.angle * 57.29578f)
            TowerKind.LANCE -> drawSpriteFrameCentered(canvas, sprites.lanceTurret, firingFrame, x, y - tileSize * 0.02f, tileSize * 0.88f, tower.angle * 57.29578f)
            TowerKind.MIRE -> drawSpriteFrameCentered(canvas, sprites.mireTurret, firingFrame, x, y - tileSize * 0.06f, tileSize * (0.78f + sin(ambientTime * 5f) * 0.03f), tower.angle * 57.29578f)
            TowerKind.GALE -> drawSpriteFrameCentered(canvas, sprites.galeTurret, firingFrame, x, y - tileSize * 0.06f, tileSize * (0.78f + sin(ambientTime * 5f) * 0.03f), tower.angle * 57.29578f)
            TowerKind.SUNFORGE -> drawSpriteFrameCentered(canvas, sprites.sunforgeTurret, firingFrame, x, y - tileSize * 0.06f, tileSize * (0.78f + sin(ambientTime * 5f) * 0.03f), tower.angle * 57.29578f)
            TowerKind.LODESTONE -> drawSpriteFrameCentered(canvas, sprites.lodestoneTurret, firingFrame, x, y - tileSize * 0.06f, tileSize * (0.78f + sin(ambientTime * 5f) * 0.03f), tower.angle * 57.29578f)
            TowerKind.HOWL -> drawSpriteFrameCentered(canvas, sprites.howlTurret, firingFrame, x, y - tileSize * 0.06f, tileSize * (0.78f + sin(ambientTime * 5f) * 0.03f), tower.angle * 57.29578f)
            TowerKind.VITRIOL -> drawSpriteFrameCentered(canvas, sprites.vitriolTurret, firingFrame, x, y - tileSize * 0.06f, tileSize * (0.78f + sin(ambientTime * 5f) * 0.03f), tower.angle * 57.29578f)
            TowerKind.GRAVEBOLT -> drawSpriteFrameCentered(canvas, sprites.graveboltTurret, firingFrame, x, y - tileSize * 0.06f, tileSize * (0.78f + sin(ambientTime * 5f) * 0.03f), tower.angle * 57.29578f)
            TowerKind.AEGIS_LOOM -> drawSpriteFrameCentered(canvas, sprites.aegisLoomTurret, firingFrame, x, y - tileSize * 0.06f, tileSize * (0.78f + sin(ambientTime * 5f) * 0.03f), tower.angle * 57.29578f)
        }
        if (tower.evolution != null) {
            strokePaint.strokeWidth = tileSize * 0.045f
            strokePaint.color = Color.rgb(255, 215, 104)
            canvas.drawCircle(x, y, tileSize * (0.38f + sin(ambientTime * 3f) * 0.025f), strokePaint)
            if (tower.evolveAura > 0.02f) {
                val a = (tower.evolveAura / 2.4f).coerceIn(0f, 1f)
                strokePaint.strokeWidth = tileSize * 0.03f
                strokePaint.color = Color.argb((90 * a).toInt().coerceIn(0, 255), 255, 230, 150)
                canvas.drawCircle(x, y, tileSize * (0.48f + (1f - a) * 0.06f), strokePaint)
            }
            val emblemPop = if (tower.evolveFlash > 0.02f) 1f + tower.evolveFlash * 0.55f else 1f
            drawBitmapCentered(
                canvas,
                sprites.evolution(tower.evolution!!),
                x - tileSize * 0.27f,
                y - tileSize * 0.27f,
                tileSize * 0.34f * emblemPop
            )
        }
        if (hasCoolingImmunity(tower.col, tower.row)) {
            strokePaint.strokeWidth = tileSize * 0.04f
            strokePaint.color = Color.argb(180, 93, 220, 255)
            canvas.drawCircle(x, y, tileSize * 0.44f, strokePaint)
        }
        if (tower.damageBoostTimer > 0.05f) {
            strokePaint.strokeWidth = tileSize * 0.035f
            strokePaint.color = Color.argb(200, 255, 186, 70)
            canvas.drawCircle(x, y, tileSize * 0.46f, strokePaint)
        }
        if (tower.focusBoostTimer > 0.05f) {
            strokePaint.strokeWidth = tileSize * 0.03f
            strokePaint.color = Color.argb(190, 100, 200, 255)
            canvas.drawCircle(x, y, tileSize * 0.48f, strokePaint)
        }
        if (tower.disabledTimer > 0f) {
            paint.color = Color.argb(145, 130, 48, 165)
            canvas.drawCircle(x, y, tileSize * 0.34f, paint)
            drawCenteredText(canvas, "HEX", x, y, tileSize * 0.13f, Color.WHITE, true)
        }
        drawRankDots(canvas, x, y + tileSize * 0.37f, tower.level, tower.overcharge, tower.kind.accent)
        drawImbuementGlyph(canvas, x, y, tower.imbuement)
    }

    private fun drawRankDots(canvas: Canvas, x: Float, y: Float, level: Int, overcharge: Int, accent: Int) {
        paint.color = accent
        for (rank in 0 until level) canvas.drawCircle(x + (rank - 1) * tileSize * 0.10f, y, tileSize * 0.031f, paint)
        if (overcharge > 0) {
            paint.color = Color.WHITE
            drawCenteredText(canvas, "+$overcharge", x + tileSize * 0.31f, y, max(dp(6f), tileSize * 0.11f), Color.WHITE, true)
        }
    }

    private fun drawUtility(canvas: Canvas, utility: Utility, selected: Boolean) {
        val x = cellCenterX(utility.col)
        val y = cellCenterY(utility.row)
        paint.color = Color.argb(80, 0, 0, 0)
        canvas.drawOval(x - tileSize * 0.31f, y + tileSize * 0.23f, x + tileSize * 0.31f, y + tileSize * 0.39f, paint)
        if (selected) {
            strokePaint.strokeWidth = tileSize * 0.055f
            strokePaint.color = Color.WHITE
            canvas.drawCircle(x, y, tileSize * 0.43f, strokePaint)
        }
        val strip = sprites.utility(utility.kind)
        val phase = ambientTime * 1.6f + utility.kind.ordinal * 0.85f + utility.col * 0.37f + utility.row * 0.21f
        val step = floor(phase).toInt()
        val frame = when {
            strip.frameCount >= 3 -> when (step % 4) { 0 -> 0; 1 -> 1; 2 -> 2; else -> 1 }
            strip.frameCount == 2 -> step % 2
            else -> 0
        }
        drawSpriteFrameCentered(
            canvas,
            strip,
            frame,
            x,
            y,
            tileSize * (0.86f + sin(ambientTime * 2f + utility.kind.ordinal) * 0.015f)
        )
        if (utility.disabledTimer > 0f) {
            paint.color = Color.argb(150, 130, 48, 165)
            canvas.drawCircle(x, y, tileSize * 0.34f, paint)
            drawCenteredText(canvas, "HEX", x, y, max(dp(7f), tileSize * 0.13f), Color.WHITE, true)
        }
        if (utility.kind == UtilityKind.BLOCK_GENERATOR) drawCenteredText(canvas, "+B", x, y + tileSize * 0.31f, max(dp(6f), tileSize * 0.10f), utility.kind.accent, true)
        drawRankDots(canvas, x, y + tileSize * 0.38f, utility.level, 0, utility.kind.accent)
        drawImbuementGlyph(canvas, x, y, utility.imbuement)
    }

    private fun drawImbuementGlyph(canvas: Canvas, x: Float, y: Float, imbuement: Imbuement?) {
        if (imbuement == null) return
        paint.color = Color.argb(72, Color.red(imbuement.accent), Color.green(imbuement.accent), Color.blue(imbuement.accent))
        canvas.drawCircle(x, y, tileSize * (0.42f + sin(ambientTime * 3.2f) * 0.025f), paint)
        strokePaint.strokeWidth = max(1.5f, tileSize * 0.035f)
        strokePaint.color = imbuement.accent
        canvas.drawCircle(x, y, tileSize * 0.41f, strokePaint)
        drawBitmapCentered(canvas, sprites.imbuement(imbuement), x + tileSize * 0.30f, y - tileSize * 0.29f, tileSize * 0.24f)
    }

    private fun drawEnemy(canvas: Canvas, enemy: Enemy) {
        if (!enemy.alive) return
        val dying = enemy.dying
        val deathMax = if (enemy.kind.boss) 0.55f else if (enemy.kind.elite) 0.38f else 0.28f
        val deathT = if (dying) (1f - (enemy.deathTimer / deathMax).coerceIn(0f, 1f)) else 0f
        val x = gridX(enemy.x)
        val bob = if (dying) 0f else sin(enemy.animation) * tileSize * 0.025f
        val y = gridY(enemy.y) + bob + if (dying) deathT * tileSize * 0.12f else 0f
        val size = tileSize * enemy.kind.scale * if (dying) (1f - deathT * 0.35f) else 1f
        val healthRatio = max(0f, enemy.health / enemy.maxHealth)
        val stealthFade = if (enemy.stealthed) 0.45f else 1f
        val bodyAlpha = (if (dying) (1f - deathT).coerceIn(0f, 1f) else 1f) * stealthFade
        paint.color = Color.argb((90 * bodyAlpha).toInt(), 0, 0, 0)
        canvas.drawOval(x - size * 0.34f, y + size * 0.25f, x + size * 0.34f, y + size * 0.42f, paint)
        paint.color = Color.argb(
            ((if (enemy.kind.elite || enemy.kind.boss) 115 else 70) * bodyAlpha).toInt(),
            Color.red(enemy.kind.color), Color.green(enemy.kind.color), Color.blue(enemy.kind.color)
        )
        canvas.drawCircle(x, y, size * 0.46f, paint)
        if (enemy.thornArmorTimer > 0.05f) {
            strokePaint.strokeWidth = tileSize * 0.04f
            strokePaint.color = Color.argb((180 * bodyAlpha).toInt(), 200, 255, 140)
            canvas.drawCircle(x, y, size * 0.52f, strokePaint)
        }
        if (enemy.kind.elite || enemy.kind.boss) {
            strokePaint.strokeWidth = tileSize * (if (enemy.kind.boss) 0.055f else 0.035f)
            strokePaint.color = Color.argb(
                (220 * bodyAlpha).toInt(),
                Color.red(if (enemy.kind.boss) Color.rgb(255, 207, 90) else enemy.kind.color),
                Color.green(if (enemy.kind.boss) Color.rgb(255, 207, 90) else enemy.kind.color),
                Color.blue(if (enemy.kind.boss) Color.rgb(255, 207, 90) else enemy.kind.color)
            )
            canvas.drawCircle(x, y, size * 0.50f, strokePaint)
        }
        // Hurt flash + death dissolve alpha
        val flash = enemy.flashTimer > 0f && !dying
        spritePaint.alpha = when {
            dying -> (255 * bodyAlpha * (0.55f + 0.45f * sin(enemy.animation * 8f).let { (it + 1f) * 0.5f })).toInt().coerceIn(0, 255)
            flash -> 130
            else -> 255
        }
        val enemySprite = sprites.enemy(enemy.kind)
        val animationStep = floor(enemy.animation * 1.25f).toInt()
        val animationFrame = when {
            dying && enemySprite.frameCount >= 3 -> 2
            enemySprite.frameCount >= 3 -> when (animationStep % 4) { 0 -> 0; 1 -> 1; 2 -> 2; else -> 1 }
            enemySprite.frameCount == 2 -> animationStep % 2
            else -> 0
        }
        drawSpriteFrameCentered(canvas, enemySprite, animationFrame, x, y, size * 1.02f)
        if (flash) {
            // brief white hit rim
            paint.color = Color.argb(90, 255, 255, 255)
            canvas.drawCircle(x, y, size * 0.48f, paint)
        }
        // F2 wind-up tell ring (elites + bosses while charging an ability)
        if (!dying && enemy.windupTimer > 0f) {
            val windMax = when (enemy.windupKind) {
                1 -> 1.15f; 2 -> 0.95f; 3 -> 1.25f; 4 -> 1.45f; 5 -> 1.35f; else -> 1.2f
            }
            val wind = 1f - (enemy.windupTimer / windMax).coerceIn(0f, 1f)
            val tellColor = enemy.kind.color
            strokePaint.strokeWidth = max(2f, tileSize * (if (enemy.kind.boss) 0.05f else 0.035f))
            strokePaint.color = Color.argb((90 + wind * 150).toInt(), Color.red(tellColor), Color.green(tellColor), Color.blue(tellColor))
            canvas.drawCircle(x, y, size * (0.52f + wind * 0.40f), strokePaint)
            paint.color = Color.argb((35 + wind * 55).toInt(), Color.red(tellColor), Color.green(tellColor), Color.blue(tellColor))
            canvas.drawCircle(x, y, size * (0.38f + wind * 0.18f), paint)
            if (enemy.kind.boss) {
                strokePaint.color = Color.argb((70 + wind * 100).toInt(), 255, 207, 90)
                canvas.drawCircle(x, y, size * (0.62f + wind * 0.22f), strokePaint)
            }
        } else if (!dying && enemy.pyreTrailTimer > 0f) {
            paint.color = Color.argb((50 + (sin(ambientTime * 8f) * 0.5f + 0.5f) * 40).toInt().coerceIn(0, 255), 255, 100, 40)
            canvas.drawCircle(x, y, size * 0.48f, paint)
        }
        spritePaint.alpha = 255
        if (!dying && (enemy.slowTimer > 0f || enemy.stunTimer > 0f)) {
            strokePaint.strokeWidth = max(1.5f, tileSize * 0.025f)
            strokePaint.color = if (enemy.stunTimer > 0f) Color.rgb(195, 120, 255) else Color.rgb(93, 220, 255)
            canvas.drawCircle(x, y, size * 0.53f, strokePaint)
        }
        if (!dying && enemy.burnTimer > 0f) drawSpriteFrameCentered(canvas, sprites.trap(TrapKind.EMBER), 0, x + size * 0.25f, y - size * 0.26f, size * 0.35f)
        if (!dying && (healthRatio < 0.995f || enemy.kind.elite || enemy.kind.boss)) {
            val barWidth = size * 0.90f
            val barY = y - size * 0.62f
            val barHeight = max(3f, tileSize * 0.06f)
            paint.color = Color.argb(205, 13, 19, 16)
            canvas.drawRoundRect(x - barWidth * 0.5f, barY, x + barWidth * 0.5f, barY + barHeight, tileSize * 0.03f, tileSize * 0.03f, paint)
            paint.color = if (healthRatio > 0.35f) Color.rgb(190, 244, 78) else Color.rgb(255, 91, 84)
            canvas.drawRoundRect(x - barWidth * 0.5f, barY, x - barWidth * 0.5f + barWidth * healthRatio, barY + barHeight, tileSize * 0.03f, tileSize * 0.03f, paint)
        }
        if (!dying && enemy.kind.boss) drawCenteredText(canvas, "T${enemy.bossTier}", x, y + size * 0.58f, max(dp(7f), tileSize * 0.12f), Color.rgb(255, 222, 126), true)
    }

    private fun drawProjectile(canvas: Canvas, projectile: Projectile) {
        val x = gridX(projectile.x)
        val y = gridY(projectile.y)
        val strip = sprites.projectile(projectile.kind)
        val size = when (projectile.kind) {
            TowerKind.BOLT -> tileSize * 0.42f
            TowerKind.FROST -> tileSize * 0.48f
            TowerKind.CANNON -> tileSize * 0.58f
            TowerKind.EMBER -> tileSize * 0.50f
            TowerKind.BEACON -> tileSize * 0.52f
            TowerKind.THORN -> tileSize * 0.40f
            TowerKind.LANCE -> tileSize * 0.55f
            TowerKind.MIRE -> tileSize * 0.54f
            // 1.4-era tower kinds (GALE, SUNFORGE, LODESTONE, HOWL, VITRIOL,
            // GRAVEBOLT, AEGIS_LOOM) and any future kind share a sensible default
            // instead of throwing NoWhenBranchMatchedException mid-wave.
            else -> tileSize * 0.50f
        }
        val dx = projectile.target.x - projectile.x
        val dy = projectile.target.y - projectile.y
        val angle = if (abs(dx) + abs(dy) < 0.0001f) 0f else atan2(dy, dx) * 57.29578f
        val step = floor(projectile.age * 14f).toInt()
        val frame = when {
            strip.frameCount >= 3 -> when (step % 4) { 0 -> 0; 1 -> 1; 2 -> 2; else -> 1 }
            strip.frameCount == 2 -> step % 2
            else -> 0
        }
        val hot = projectile.evolveHot
        val glowAlpha = if (hot) 95 else 50
        val glowSize = if (hot) size * 0.58f else size * 0.42f
        paint.color = Color.argb(
            glowAlpha,
            Color.red(projectile.kind.accent),
            Color.green(projectile.kind.accent),
            Color.blue(projectile.kind.accent)
        )
        canvas.drawCircle(x, y, glowSize, paint)
        if (hot) {
            // gold trail mote
            paint.color = Color.argb(70, 255, 215, 104)
            canvas.drawCircle(x, y, size * 0.28f, paint)
            drawSpriteFrameCentered(canvas, strip, frame, x, y, size * 1.12f, angle)
        } else {
            drawSpriteFrameCentered(canvas, strip, frame, x, y, size, angle)
        }
    }

    private fun drawImpact(canvas: Canvas, fx: ImpactFx) {
        val x = gridX(fx.x)
        val y = gridY(fx.y)
        val strip = sprites.impact(fx.kind)
        val size = when (fx.kind) {
            TowerKind.BOLT -> tileSize * 0.72f
            TowerKind.FROST -> tileSize * 0.78f
            TowerKind.CANNON -> tileSize * 0.95f
            TowerKind.EMBER -> tileSize * 0.85f
            TowerKind.BEACON -> tileSize * 0.88f
            TowerKind.THORN -> tileSize * 0.70f
            TowerKind.LANCE -> tileSize * 0.82f
            TowerKind.MIRE -> tileSize * 0.90f
            // 1.4-era tower kinds and any future kind: default impact size.
            else -> tileSize * 0.85f
        }
        val t = (fx.age / fx.duration).coerceIn(0f, 0.999f)
        val frame = when {
            strip.frameCount >= 3 -> when {
                t < 0.28f -> 0
                t < 0.62f -> 1
                else -> 2
            }
            strip.frameCount == 2 -> if (t < 0.5f) 0 else 1
            else -> 0
        }
        val fade = when {
            t < 0.15f -> t / 0.15f
            t > 0.72f -> ((1f - t) / 0.28f).coerceIn(0f, 1f)
            else -> 1f
        }
        paint.color = Color.argb(
            (42 * fade).toInt().coerceIn(0, 255),
            Color.red(fx.kind.accent),
            Color.green(fx.kind.accent),
            Color.blue(fx.kind.accent)
        )
        canvas.drawCircle(x, y, size * 0.38f, paint)
        spritePaint.alpha = (255 * fade).toInt().coerceIn(0, 255)
        drawSpriteFrameCentered(canvas, strip, frame, x, y, size * (0.88f + 0.18f * t), 0f)
        spritePaint.alpha = 255
    }

    private fun drawParticle(canvas: Canvas, particle: Particle) {
        val alphaRatio = max(0f, particle.life / particle.maxLife)
        paint.color = Color.argb((alphaRatio * 220f).toInt(), Color.red(particle.color), Color.green(particle.color), Color.blue(particle.color))
        val x = gridX(particle.x)
        val y = gridY(particle.y)
        val size = max(2f, particle.size * tileSize * alphaRatio)
        if (particle.square) canvas.drawRect(x - size, y - size, x + size, y + size, paint) else canvas.drawCircle(x, y, size, paint)
        // No canvas.restore() here: drawBoard saves the F0 viewport clip exactly once per
        // frame, so restoring per particle underflows the canvas save stack ("Underflow in
        // restore - more restores than saves") as soon as two particles draw in one frame.
        // The matching restore lives at the end of drawBoard.
    }

    private fun drawTopBar(canvas: Canvas) {
        paint.color = Color.rgb(13, 22, 17)
        canvas.drawRect(0f, 0f, viewWidth, topBarHeight, paint)
        paint.color = Color.rgb(190, 244, 78)
        canvas.drawRect(0f, topBarHeight - max(2f, dp(2f)), viewWidth, topBarHeight, paint)

        drawRoundedRect(canvas, dp(10f), dp(8f), dp(48f), topBarHeight - dp(8f), dp(10f), Color.rgb(190, 244, 78))
        drawCenteredText(canvas, "B", dp(29f), topBarHeight * 0.5f, min(dp(21f), topBarHeight * 0.42f), Color.rgb(13, 22, 17), true, true)
        drawText(canvas, "BLOCKHOLD", dp(58f), topBarHeight * 0.43f, min(dp(14f), topBarHeight * 0.27f), Color.WHITE, Paint.Align.LEFT, true, true)
        val forgeResources = if (phase == GamePhase.WAVE) "F$forgeCharges E$evolutionCores" else "F$forgeCharges E$evolutionCores P$salvageParts G$growthEssence"
        drawText(canvas, "${phaseLabel()} • $forgeResources", dp(58f), topBarHeight * 0.72f, min(dp(7.5f), topBarHeight * 0.16f), Color.rgb(139, 157, 144), Paint.Align.LEFT, true)

        val statStart = min(viewWidth * 0.27f, dp(265f))
        val statGap = min(dp(102f), viewWidth * 0.115f)
        drawStat(canvas, statStart, "BLOCKS", formatNumber(gold), Color.rgb(190, 244, 78), goldPulse)
        drawStat(canvas, statStart + statGap, "CORE", "$lives/$maxCore", Color.rgb(255, 111, 100))
        drawStat(canvas, statStart + statGap * 2f, "WAVE", waveNumber.toString(), Color.rgb(93, 220, 255))
        // Forge charge pulse on the resource strip under the title
        if (forgePulse > 0.02f) {
            paint.color = Color.argb((forgePulse * 90f).toInt().coerceIn(0, 255), 255, 187, 116)
            canvas.drawCircle(dp(72f), topBarHeight * 0.72f, dp(14f) * forgePulse, paint)
        }

        when {
            phase == GamePhase.REFORGE -> drawTopButton(canvas, resetPathRect, "CANCEL", Color.rgb(67, 42, 37), Color.rgb(255, 188, 126), true)
            waveNumber == 0 && (phase == GamePhase.DIG || phase == GamePhase.BUILD) -> drawTopButton(canvas, resetPathRect, "RESET", Color.rgb(36, 50, 40), Color.rgb(214, 224, 216), true)
            phase == GamePhase.BUILD -> drawTopButton(canvas, resetPathRect, "REFORGE $forgeCharges", Color.rgb(53, 43, 68), Color.rgb(213, 182, 255), true)
        }
        val canStart = (phase == GamePhase.BUILD && pathComplete) || (phase == GamePhase.REFORGE && pathComplete && effectiveReforgeCost() <= forgeCharges && reforgeRecoveryCost() <= gold && storedTraps.size + displacedReforgeTraps().size <= cacheCapacity())
        val actionLabel = when (phase) {
            GamePhase.DIG -> "CONNECT CORE"
            GamePhase.BUILD -> "START WAVE ${waveNumber + 1}"
            GamePhase.REFORGE -> "CONFIRM F${effectiveReforgeCost()}  B${reforgeRecoveryCost()}"
            GamePhase.WAVE -> "WAVE LIVE"
            else -> "STANDBY"
        }
        drawTopButton(canvas, primaryActionRect, actionLabel, if (canStart) Color.rgb(190, 244, 78) else Color.rgb(31, 44, 35), if (canStart) Color.rgb(13, 22, 17) else Color.rgb(104, 123, 110), canStart)
        drawTopButton(canvas, soundRect, if (audio.isEnabled()) "SFX" else "OFF", Color.rgb(31, 44, 35), Color.WHITE, true)
        drawTopButton(canvas, pauseRect, "II", Color.rgb(31, 44, 35), Color.WHITE, true)
    }

    private fun drawStat(canvas: Canvas, x: Float, label: String, value: String, accent: Int, pulse: Float = 0f) {
        drawText(canvas, label, x, topBarHeight * 0.37f, min(dp(8f), topBarHeight * 0.16f), Color.rgb(115, 135, 121), Paint.Align.LEFT, true)
        val valueSize = min(dp(15f), topBarHeight * 0.30f) * (1f + pulse * 0.18f)
        if (pulse > 0.02f) {
            paint.color = Color.argb((pulse * 70f).toInt().coerceIn(0, 255), Color.red(accent), Color.green(accent), Color.blue(accent))
            canvas.drawCircle(x + dp(22f), topBarHeight * 0.62f, dp(16f) * pulse, paint)
        }
        drawText(canvas, value, x, topBarHeight * 0.70f, valueSize, accent, Paint.Align.LEFT, true, true)
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
            GamePhase.DIG -> if (gameMode == GameMode.ENDLESS) "PATH FORGE" else "${gameMode.name} $runSeed"
            GamePhase.BUILD -> if (gameMode == GameMode.ENDLESS) "ENDLESS BUILD" else challengeModifier.title.uppercase()
            GamePhase.REFORGE -> "PATH REFORGE"
            GamePhase.PERK_DRAFT -> "FORGE PERK"
            GamePhase.EVOLUTION_DRAFT -> "EVOLUTION"
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
            phase == GamePhase.DIG || phase == GamePhase.REFORGE -> drawPathForgePanel(canvas)
            selectedCorruption != null && phase == GamePhase.BUILD -> drawCorruptionPanel(canvas)
            selectedUtility != null && phase == GamePhase.BUILD -> drawUtilityPanel(canvas)
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
        val reforging = phase == GamePhase.REFORGE
        drawCenteredText(canvas, if (reforging) "PREVIEW THE NEW ROUTE" else "DRAW THE ONLY ROUTE", left + width * 0.31f, top + (bottom - top) * 0.38f, min(dp(15f), bottomBarHeight * 0.16f), Color.rgb(190, 244, 78), true, true)
        drawCenteredText(canvas, if (reforging) "STRUCTURES BLOCK THE PATH • F${effectiveReforgeCost()} • RECOVERY ${reforgeRecoveryCost()} B • ${displacedReforgeTraps().size} TRAPS" else "DRAG BLOCK BY BLOCK • BACKTRACK TO UNDO", left + width * 0.31f, top + (bottom - top) * 0.69f, min(dp(9f), bottomBarHeight * 0.095f), Color.rgb(172, 188, 177), true)
        drawBitmapCentered(canvas, sprites.path, left + width * 0.72f, (top + bottom) * 0.5f, min(bottom - top, dp(76f)))
        val limit = currentPathLimit()
        drawCenteredText(canvas, "${pathCells.size}/$limit", left + width * 0.89f, (top + bottom) * 0.5f, dp(13f), Color.WHITE, true)
    }

    private fun drawToolBar(canvas: Canvas) {
        drawPageTab(canvas, towerPageRect, if (challengeModifier == ChallengeModifier.TRAPS_ONLY) "LOCK" else "TWR ${towerPageIndex + 1}/4", buildPage == BuildPage.TOWERS, Color.rgb(190, 244, 78))
        drawPageTab(canvas, trapPageRect, if (challengeModifier == ChallengeModifier.TOWERS_ONLY) "LOCK" else "TRAP", buildPage == BuildPage.TRAPS, Color.rgb(93, 220, 255))
        drawPageTab(canvas, utilityPageRect, "UTIL ${utilityPageIndex + 1}/5", buildPage == BuildPage.UTILITIES, Color.rgb(255, 203, 81))
        drawPageTab(canvas, cachePageRect, "CACHE ${storedTraps.size}/${cacheCapacity()}", buildPage == BuildPage.CACHE, Color.rgb(195, 120, 255))
        for ((tool, rect) in toolRects) {
            val selected = selectedTool == tool && ((buildPage == BuildPage.TOWERS && tool.ordinal < BuildTool.SPIKES.ordinal) || (buildPage == BuildPage.TRAPS && tool.ordinal >= BuildTool.SPIKES.ordinal))
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
        for ((kind, rect) in utilityRects) {
            val selected = selectedUtilityKind == kind
            val unlocked = utilityUnlocked(kind)
            drawRoundedRect(canvas, rect.left, rect.top, rect.right, rect.bottom, dp(11f), if (selected) kind.accent else Color.rgb(25, 38, 30))
            if (selected) { strokePaint.strokeWidth = dp(2f); strokePaint.color = Color.WHITE; canvas.drawRoundRect(rect, dp(11f), dp(11f), strokePaint) }
            spritePaint.alpha = if (unlocked) 255 else 95
            drawSpriteFrameCentered(
                canvas,
                sprites.utility(kind),
                1,
                rect.centerX(),
                rect.top + rect.height() * 0.35f,
                min(rect.width(), rect.height()) * 0.54f
            )
            spritePaint.alpha = 255
            drawCenteredText(canvas, kind.title.uppercase(), rect.centerX(), rect.top + rect.height() * 0.70f, min(dp(8f), rect.width() * 0.09f), if (unlocked) Color.WHITE else Color.rgb(105, 116, 108), true)
            drawCenteredText(canvas, if (unlocked) kind.cost.toString() else "WAVE 10", rect.centerX(), rect.top + rect.height() * 0.88f, min(dp(8f), rect.width() * 0.09f), if (unlocked && gold >= kind.cost) Color.rgb(190, 244, 78) else Color.rgb(255, 111, 100), true)
        }
        for ((index, rect) in cacheRects) {
            val stored = storedTraps[index]
            val selected = selectedStoredTrapIndex == index
            drawRoundedRect(canvas, rect.left, rect.top, rect.right, rect.bottom, dp(11f), if (selected) stored.kind.accent else Color.rgb(25, 38, 30))
            if (selected) { strokePaint.strokeWidth = dp(2f); strokePaint.color = Color.WHITE; canvas.drawRoundRect(rect, dp(11f), dp(11f), strokePaint) }
            drawSpriteFrameCentered(canvas, sprites.trap(stored.kind), 0, rect.centerX(), rect.top + rect.height() * 0.34f, min(rect.width(), rect.height()) * 0.50f)
            drawCenteredText(canvas, stored.kind.title.uppercase(), rect.centerX(), rect.top + rect.height() * 0.69f, min(dp(8f), rect.width() * 0.09f), if (selected) Color.rgb(13, 22, 17) else Color.WHITE, true)
            drawCenteredText(canvas, stored.rankLabel(), rect.centerX(), rect.top + rect.height() * 0.87f, min(dp(7f), rect.width() * 0.08f), if (stored.imbuement != null) stored.imbuement!!.accent else Color.rgb(190, 244, 78), true)
        }
        if (buildPage == BuildPage.CACHE && storedTraps.isEmpty()) drawCenteredText(canvas, "CACHE EMPTY  •  STORE A PLACED TRAP", (cachePageRect.right + viewWidth) * 0.5f, viewHeight - bottomBarHeight * 0.5f, dp(11f), Color.rgb(137, 153, 142), true)
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
            BuildTool.THORN -> TowerKind.THORN.accent
            BuildTool.LANCE -> TowerKind.LANCE.accent
            BuildTool.MIRE -> TowerKind.MIRE.accent
            BuildTool.GALE -> TowerKind.GALE.accent
            BuildTool.SUNFORGE -> TowerKind.SUNFORGE.accent
            BuildTool.LODESTONE -> TowerKind.LODESTONE.accent
            BuildTool.HOWL -> TowerKind.HOWL.accent
            BuildTool.VITRIOL -> TowerKind.VITRIOL.accent
            BuildTool.GRAVEBOLT -> TowerKind.GRAVEBOLT.accent
            BuildTool.AEGIS_LOOM -> TowerKind.AEGIS_LOOM.accent
            BuildTool.SPIKES -> TrapKind.SPIKE.accent
            BuildTool.ROOT -> TrapKind.ROOT.accent
            BuildTool.RUNE -> TrapKind.EMBER.accent
            BuildTool.ARC -> TrapKind.ARC.accent
            BuildTool.CRUSHER -> TrapKind.CRUSHER.accent
            BuildTool.DIG -> Color.rgb(153, 125, 82)
        }
    }

    private fun toolTrapKind(tool: BuildTool): TrapKind? {
        return when (tool) {
            BuildTool.SPIKES -> TrapKind.SPIKE
            BuildTool.ROOT -> TrapKind.ROOT
            BuildTool.RUNE -> TrapKind.EMBER
            BuildTool.ARC -> TrapKind.ARC
            BuildTool.CRUSHER -> TrapKind.CRUSHER
            else -> null
        }
    }

    private fun drawToolIcon(canvas: Canvas, tool: BuildTool, x: Float, y: Float, size: Float, color: Int) {
        val towerLayers: Pair<Bitmap, SpriteStrip>? = when (tool) {
            BuildTool.BOLT -> Pair(sprites.towerBase, sprites.greenTurret)
            BuildTool.FROST -> Pair(sprites.frostBase, sprites.paleTurret)
            BuildTool.CANNON -> Pair(sprites.cannonBase, sprites.cannonTurret)
            BuildTool.EMBER -> Pair(sprites.emberBase, sprites.emberFlame)
            BuildTool.BEACON -> Pair(sprites.beaconBase, sprites.beaconPulse)
            BuildTool.THORN -> Pair(sprites.thornBase, sprites.thornTurret)
            BuildTool.LANCE -> Pair(sprites.lanceBase, sprites.lanceTurret)
            BuildTool.MIRE -> Pair(sprites.mireBase, sprites.mireTurret)
            BuildTool.GALE -> Pair(sprites.galeBase, sprites.galeTurret)
            BuildTool.SUNFORGE -> Pair(sprites.sunforgeBase, sprites.sunforgeTurret)
            BuildTool.LODESTONE -> Pair(sprites.lodestoneBase, sprites.lodestoneTurret)
            BuildTool.HOWL -> Pair(sprites.howlBase, sprites.howlTurret)
            BuildTool.VITRIOL -> Pair(sprites.vitriolBase, sprites.vitriolTurret)
            BuildTool.GRAVEBOLT -> Pair(sprites.graveboltBase, sprites.graveboltTurret)
            BuildTool.AEGIS_LOOM -> Pair(sprites.aegisLoomBase, sprites.aegisLoomTurret)
            else -> null
        }
        if (towerLayers != null) {
            drawBitmapCentered(canvas, towerLayers.first, x, y + size * 0.08f, size * 2.1f)
            drawSpriteFrameCentered(canvas, towerLayers.second, 0, x, y, size * 2.0f)
            return
        }
        if (tool == BuildTool.EMBER || tool == BuildTool.BEACON) {
            val base = if (tool == BuildTool.EMBER) sprites.emberBase else sprites.beaconBase
            val effect = if (tool == BuildTool.EMBER) sprites.emberFlame else sprites.beaconPulse
            val effectSize = if (tool == BuildTool.EMBER) size * 0.96f else size * 1.22f
            drawBitmapCentered(canvas, base, x, y + size * 0.08f, size * 2.1f)
            drawSpriteFrameCentered(canvas, effect, 0, x, y - size * 0.08f, effectSize)
            return
        }
        val trapKind = toolTrapKind(tool)
        if (trapKind != null) {
            drawSpriteFrameCentered(canvas, sprites.trap(trapKind), 0, x, y, size * 2.1f)
            return
        }
        paint.color = color
        canvas.drawRect(x - size * 0.45f, y - size * 0.14f, x + size * 0.45f, y + size * 0.14f, paint)
    }

    private fun drawDefensePanel(canvas: Canvas) {
        val tower = selectedTower
        val trap = selectedTrap
        val title = if (tower != null && tower.evolution != null) tower.evolution!!.title else tower?.kind?.title ?: trap?.kind?.title ?: return
        val accent = tower?.kind?.accent ?: trap?.kind?.accent ?: Color.WHITE
        var cost = tower?.upgradeCost() ?: trap?.upgradeCost() ?: 0
        if ((tower?.level ?: trap?.level ?: 1) >= 3) cost = max(1, cost - (cost * perkCount(ForgePerk.EFFICIENT_OVERCHARGE) * 0.20f).toInt())
        val canBuy = gold >= cost
        val damage = tower?.currentDamage() ?: trap?.currentDamage() ?: 0f
        val range = tower?.currentRange()
        val rank = tower?.rankLabel() ?: trap?.rankLabel().orEmpty()
        val sellValue = tower?.sellValue(recyclingMultiplier()) ?: trap?.sellValue(recyclingMultiplier()) ?: 0
        drawRoundedRect(canvas, backRect.left, backRect.top, backRect.right, backRect.bottom, dp(12f), Color.rgb(25, 38, 30))
        drawCenteredText(canvas, "BACK", backRect.centerX(), backRect.centerY(), dp(11f), Color.WHITE, true)
        val upgradeColor = if (canBuy) accent else Color.rgb(27, 42, 33)
        drawRoundedRect(canvas, upgradeRect.left, upgradeRect.top, upgradeRect.right, upgradeRect.bottom, dp(12f), upgradeColor)
        val titleColor = if (canBuy) Color.rgb(12, 21, 16) else Color.rgb(150, 168, 156)
        drawCenteredText(canvas, "UPGRADE ${title.uppercase()}  •  $rank", upgradeRect.centerX(), upgradeRect.top + upgradeRect.height() * 0.37f, min(dp(10f), upgradeRect.width() * 0.030f), titleColor, true)
        val stats = if (range != null) "DMG ${damage.toInt()}  RANGE ${oneDecimal(range)}  •  $cost BLOCKS" else "DMG ${damage.toInt()}  •  $cost BLOCKS"
        drawCenteredText(canvas, stats, upgradeRect.centerX(), upgradeRect.top + upgradeRect.height() * 0.70f, min(dp(9f), upgradeRect.width() * 0.026f), titleColor, true)
        val canEvolve = tower?.canEvolve() == true
        val storeLabel = when { canEvolve -> "EVOLVE • $evolutionCores CORE"; trap != null -> "STORE • ${trapStorageCost(trap)} B"; else -> "EVOLUTION LOCKED" }
        drawRoundedRect(canvas, storeRect.left, storeRect.top, storeRect.right, storeRect.bottom, dp(7f), if (canEvolve) Color.rgb(68, 55, 30) else if (trap != null) Color.rgb(35, 54, 68) else Color.rgb(31, 40, 34))
        drawCenteredText(canvas, storeLabel, storeRect.centerX(), storeRect.centerY(), min(dp(8f), storeRect.height() * 0.43f), if (canEvolve) Color.rgb(255, 222, 126) else Color.rgb(186, 221, 241), true)
        val existingImbuement = tower?.imbuement ?: trap?.imbuement
        drawRoundedRect(canvas, imbueRect.left, imbueRect.top, imbueRect.right, imbueRect.bottom, dp(7f), Color.rgb(53, 43, 68))
        drawCenteredText(canvas, if (existingImbuement == null) "IMBUE" else "IMBUED • ${existingImbuement.title.uppercase()}", imbueRect.centerX(), imbueRect.centerY(), min(dp(8f), imbueRect.height() * 0.43f), existingImbuement?.accent ?: Color.rgb(213, 182, 255), true)
        val recycleLocked = challengeModifier == ChallengeModifier.NO_RECYCLING
        drawRoundedRect(canvas, sellRect.left, sellRect.top, sellRect.right, sellRect.bottom, dp(7f), if (recycleLocked) Color.rgb(35, 36, 34) else Color.rgb(50, 37, 32))
        drawCenteredText(canvas, if (recycleLocked) "NO RECYCLING" else "RECYCLE • +$sellValue", sellRect.centerX(), sellRect.centerY(), min(dp(8f), sellRect.height() * 0.43f), Color.rgb(255, 188, 126), true)
    }

    private fun drawUtilityPanel(canvas: Canvas) {
        val utility = selectedUtility ?: return
        val cost = if (utility.level < 3) utility.upgradeCost() else 0
        drawRoundedRect(canvas, backRect.left, backRect.top, backRect.right, backRect.bottom, dp(12f), Color.rgb(25, 38, 30))
        drawCenteredText(canvas, "BACK", backRect.centerX(), backRect.centerY(), dp(10f), Color.WHITE, true)
        drawRoundedRect(canvas, upgradeRect.left, upgradeRect.top, upgradeRect.right, upgradeRect.bottom, dp(12f), if (utility.level < 3 && gold >= cost) utility.kind.accent else Color.rgb(31, 43, 35))
        drawCenteredText(canvas, "${utility.kind.title.uppercase()}  •  LEVEL ${utility.level}", upgradeRect.centerX(), upgradeRect.top + upgradeRect.height() * 0.33f, min(dp(10f), upgradeRect.width() * 0.03f), if (utility.level < 3 && gold >= cost) Color.rgb(12, 21, 16) else Color.WHITE, true)
        drawWrappedText(canvas, if (utility.level < 3) "${utility.kind.description} • UPGRADE $cost BLOCKS" else "${utility.kind.description} • MAXIMUM LEVEL", upgradeRect.centerX(), upgradeRect.top + upgradeRect.height() * 0.68f, upgradeRect.width() * 0.90f, dp(8f), if (utility.level < 3 && gold >= cost) Color.rgb(22, 38, 27) else Color.rgb(168, 184, 172), 2)
        drawRoundedRect(canvas, storeRect.left, storeRect.top, storeRect.right, storeRect.bottom, dp(7f), if (utility.kind == UtilityKind.FORGE_WORKSHOP) Color.rgb(81, 49, 31) else Color.rgb(31, 40, 34))
        drawCenteredText(canvas, if (utility.kind == UtilityKind.FORGE_WORKSHOP) "OPEN FORGEWORKS" else "PASSIVE UTILITY", storeRect.centerX(), storeRect.centerY(), min(dp(8f), storeRect.height() * 0.43f), if (utility.kind == UtilityKind.FORGE_WORKSHOP) Color.rgb(255, 187, 116) else Color.rgb(137, 153, 142), true)
        drawRoundedRect(canvas, imbueRect.left, imbueRect.top, imbueRect.right, imbueRect.bottom, dp(7f), Color.rgb(53, 43, 68))
        drawCenteredText(canvas, if (utility.imbuement == null) "IMBUE" else "IMBUED • ${utility.imbuement!!.title.uppercase()}", imbueRect.centerX(), imbueRect.centerY(), min(dp(8f), imbueRect.height() * 0.43f), utility.imbuement?.accent ?: Color.rgb(213, 182, 255), true)
        drawRoundedRect(canvas, sellRect.left, sellRect.top, sellRect.right, sellRect.bottom, dp(7f), Color.rgb(50, 37, 32))
        drawCenteredText(canvas, if (challengeModifier == ChallengeModifier.NO_RECYCLING) "NO RECYCLING" else "RECYCLE UTILITY", sellRect.centerX(), sellRect.centerY(), min(dp(8f), sellRect.height() * 0.43f), Color.rgb(255, 188, 126), true)
    }

    private fun drawCorruptionPanel(canvas: Canvas) {
        val corruption = selectedCorruption ?: return
        val cost = corruptionCleanseCost(corruption)
        val vial = corruptionUsesVial(corruption)
        drawRoundedRect(canvas, backRect.left, backRect.top, backRect.right, backRect.bottom, dp(12f), Color.rgb(25, 38, 30))
        drawCenteredText(canvas, "BACK", backRect.centerX(), backRect.centerY(), dp(11f), Color.WHITE, true)
        drawRoundedRect(canvas, upgradeRect.left, upgradeRect.top, upgradeRect.right, upgradeRect.bottom, dp(12f), if (forgeCharges >= cost) corruption.kind.accent else Color.rgb(42, 37, 42))
        drawCenteredText(canvas, "CLEANSE ${corruption.kind.title.uppercase()}", upgradeRect.centerX(), upgradeRect.top + upgradeRect.height() * 0.37f, dp(10f), Color.WHITE, true)
        drawWrappedText(canvas, corruption.kind.description, upgradeRect.centerX(), upgradeRect.top + upgradeRect.height() * 0.69f, upgradeRect.width() * 0.90f, dp(8f), Color.rgb(224, 230, 225), 2)
        drawRoundedRect(canvas, sellRect.left, sellRect.top, sellRect.right, sellRect.bottom, dp(12f), Color.rgb(53, 43, 68))
        drawCenteredText(canvas, "$cost FORGE${if (vial) " + VIAL" else ""}", sellRect.centerX(), sellRect.top + sellRect.height() * 0.40f, dp(10f), Color.rgb(213, 182, 255), true)
        drawCenteredText(canvas, "YOU HAVE $forgeCharges", sellRect.centerX(), sellRect.top + sellRect.height() * 0.70f, dp(8f), Color.rgb(175, 157, 194), true)
    }

    private fun drawBanner(canvas: Canvas) {
        val timed = bannerTimer > 0f
        val instruction = when {
            timed -> bannerText
            phase == GamePhase.DIG -> "DRAG ONE BLOCK AT A TIME  •  BACKTRACK TO UNDO  •  MAX ${currentPathLimit()}"
            phase == GamePhase.REFORGE -> "PREVIEW • CONFIRM/CANCEL • F${effectiveReforgeCost()}/$forgeCharges • RECOVERY ${reforgeRecoveryCost()} B • CACHE ${storedTraps.size + displacedReforgeTraps().size}/${cacheCapacity()}"
            phase == GamePhase.BUILD && waveNumber == 0 && towers.isEmpty() -> "CHOOSE A DEFENSE BELOW  •  TAP A FREE BLOCK TO BUILD"
            phase == GamePhase.BUILD && surveyAvailable() -> surveyPreviewText(waveNumber + 1)
            phase == GamePhase.BUILD -> "BUILD DEFENSES AND INFRASTRUCTURE  •  REFORGE BETWEEN WAVES"
            phase == GamePhase.WAVE -> "$waveTheme  •  TOWERS ARE AUTONOMOUS"
            else -> ""
        }
        if (instruction.isEmpty()) return
        val elapsed = if (timed) (bannerDuration - bannerTimer).coerceAtLeast(0f) else 0f
        val enter = if (timed) (elapsed / 0.22f).coerceIn(0f, 1f) else 1f
        // Ease-out entrance
        val enterEase = 1f - (1f - enter) * (1f - enter)
        val fadeOut = if (timed && bannerTimer in 0f..0.38f) (bannerTimer / 0.38f) else 1f
        val alpha = (if (timed) 235f * fadeOut else 200f).toInt().coerceIn(0, 255)
        val baseWidth = min(viewWidth * 0.69f, dp(610f))
        val baseHeight = min(dp(28f), tileSize * 0.48f)
        val widthScale = if (timed) 0.82f + 0.18f * enterEase else 1f
        val heightScale = if (timed) 0.88f + 0.20f * enterEase else 1f
        val width = baseWidth * widthScale
        val height = baseHeight * heightScale
        val left = (viewWidth - width) * 0.5f
        val top = boardTop + dp(7f) - (1f - enterEase) * dp(10f)
        // Accent rim on timed banners (wave / forge / boss)
        if (timed) {
            val rim = Color.argb((alpha * 0.55f).toInt().coerceIn(0, 255), 190, 244, 78)
            drawRoundedRect(canvas, left - dp(2f), top - dp(2f), left + width + dp(2f), top + height + dp(2f), height * 0.5f, rim)
            // Soft glow under bar
            paint.color = Color.argb((alpha * 0.18f).toInt().coerceIn(0, 255), 190, 244, 78)
            canvas.drawRoundRect(left, top + height * 0.35f, left + width, top + height + dp(6f), height * 0.45f, height * 0.45f, paint)
        }
        drawRoundedRect(canvas, left, top, left + width, top + height, height * 0.45f, Color.argb(alpha, 14, 23, 18))
        val textSize = min(dp(9f), height * 0.34f) * (if (timed) 0.92f + 0.12f * enterEase else 1f)
        drawCenteredText(
            canvas,
            instruction,
            viewWidth * 0.5f,
            top + height * 0.52f,
            textSize,
            Color.argb(min(255, alpha + 25), 224, 232, 226),
            true
        )
        // Thin progress tick for timed announcements
        if (timed && bannerDuration > 0.01f) {
            val progress = (bannerTimer / bannerDuration).coerceIn(0f, 1f)
            paint.color = Color.argb((alpha * 0.85f).toInt().coerceIn(0, 255), 190, 244, 78)
            canvas.drawRect(left + dp(8f), top + height - dp(3f), left + dp(8f) + (width - dp(16f)) * progress, top + height - dp(1f), paint)
        }
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
        val modeBest = when (gameMode) { GameMode.ENDLESS -> bestWave; GameMode.DAILY -> bestDailyWave; GameMode.CUSTOM -> bestCustomWave }
        drawResultStat(canvas, cardLeft + cardWidth * 0.82f, cardTop, cardBottom, "BEST WAVE", modeBest.toString(), Color.rgb(255, 188, 96))
        drawEndButtons(canvas, if (gameMode == GameMode.ENDLESS) "NEW RUN" else "RETRY SEED", "MAIN MENU")
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

    private fun drawSpriteFrameCentered(canvas: Canvas, strip: SpriteStrip, frame: Int, x: Float, y: Float, size: Float, rotation: Float = 0f) {
        val frameIndex = frame.coerceIn(0, strip.frameCount - 1)
        val source = Rect(frameIndex * strip.frameWidth, 0, (frameIndex + 1) * strip.frameWidth, strip.bitmap.height)
        val scale = size / max(strip.frameWidth, strip.bitmap.height).toFloat()
        val halfWidth = strip.frameWidth * scale * 0.5f
        val halfHeight = strip.bitmap.height * scale * 0.5f
        val destination = RectF(x - halfWidth, y - halfHeight, x + halfWidth, y + halfHeight)
        if (rotation != 0f) {
            canvas.save()
            canvas.rotate(rotation, x, y)
            canvas.drawBitmap(strip.bitmap, source, destination, spritePaint)
            canvas.restore()
        } else {
            canvas.drawBitmap(strip.bitmap, source, destination, spritePaint)
        }
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

    private fun drawWrappedText(canvas: Canvas, value: String, centerX: Float, centerY: Float, maxWidth: Float, size: Float, color: Int, maxLines: Int, bold: Boolean = false) {
        paint.textSize = size
        paint.typeface = if (bold) boldTypeface else regularTypeface
        val words = value.split(' ')
        val lines = ArrayList<String>()
        var line = ""
        for (word in words) {
            val candidate = if (line.isEmpty()) word else "$line $word"
            if (paint.measureText(candidate) <= maxWidth || line.isEmpty()) line = candidate else {
                lines.add(line)
                line = word
                if (lines.size >= maxLines - 1) break
            }
        }
        if (line.isNotEmpty() && lines.size < maxLines) lines.add(line)
        val spacing = size * 1.35f
        val start = centerY - (lines.size - 1) * spacing * 0.5f
        lines.forEachIndexed { index, text -> drawCenteredText(canvas, text, centerX, start + index * spacing, size, color, bold) }
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

    private fun applyCameraTransform() {
        tileSize = max(12f, baseTileSize * cameraZoom)
        val boardW = tileSize * COLS
        val boardH = tileSize * ROWS
        clampCamera()
        boardLeft = viewportLeft + (viewportRight - viewportLeft - boardW) * 0.5f + cameraPanX
        boardTop = viewportTop + (viewportBottom - viewportTop - boardH) * 0.5f + cameraPanY
    }

    private fun clampCamera() {
        val boardW = tileSize * COLS
        val boardH = tileSize * ROWS
        val viewW = viewportRight - viewportLeft
        val viewH = viewportBottom - viewportTop
        if (boardW <= viewW + 0.5f) {
            cameraPanX = 0f
        } else {
            val maxPan = (boardW - viewW) * 0.5f + dp(8f)
            cameraPanX = cameraPanX.coerceIn(-maxPan, maxPan)
        }
        if (boardH <= viewH + 0.5f) {
            cameraPanY = 0f
        } else {
            val maxPan = (boardH - viewH) * 0.5f + dp(8f)
            cameraPanY = cameraPanY.coerceIn(-maxPan, maxPan)
        }
    }

    private fun resetCamera() {
        cameraZoom = 1f
        cameraPanX = 0f
        cameraPanY = 0f
        cameraGesture = false
        pinchActive = false
        suppressGridTap = false
        if (baseTileSize > 0f) applyCameraTransform()
    }

    private fun pointerDistance(event: MotionEvent): Float {
        if (event.pointerCount < 2) return 1f
        val dx = event.getX(0) - event.getX(1)
        val dy = event.getY(0) - event.getY(1)
        return max(1f, sqrt(dx * dx + dy * dy))
    }

    private fun inBoardViewport(x: Float, y: Float): Boolean {
        return x >= viewportLeft && x <= viewportRight && y >= viewportTop && y <= viewportBottom
    }

    private fun handleCameraTouch(event: MotionEvent): Boolean {
        val action = event.actionMasked
        when (action) {
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount >= 2 && inBoardViewport(
                        (event.getX(0) + event.getX(1)) * 0.5f,
                        (event.getY(0) + event.getY(1)) * 0.5f
                    )
                ) {
                    diggingGesture = false
                    pinchActive = true
                    cameraGesture = true
                    suppressGridTap = true
                    pinchStartDistance = pointerDistance(event)
                    pinchStartZoom = cameraZoom
                    pinchFocusX = (event.getX(0) + event.getX(1)) * 0.5f
                    pinchFocusY = (event.getY(0) + event.getY(1)) * 0.5f
                    pinchStartPanX = cameraPanX
                    pinchStartPanY = cameraPanY
                    return true
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (pinchActive && event.pointerCount >= 2) {
                    val dist = pointerDistance(event)
                    val factor = dist / max(1f, pinchStartDistance)
                    val focusX = (event.getX(0) + event.getX(1)) * 0.5f
                    val focusY = (event.getY(0) + event.getY(1)) * 0.5f
                    cameraZoom = (pinchStartZoom * factor).coerceIn(MIN_CAMERA_ZOOM, MAX_CAMERA_ZOOM)
                    val zoomRatio = cameraZoom / max(0.01f, pinchStartZoom)
                    cameraPanX = pinchStartPanX * zoomRatio + (focusX - pinchFocusX)
                    cameraPanY = pinchStartPanY * zoomRatio + (focusY - pinchFocusY)
                    applyCameraTransform()
                    suppressGridTap = true
                    return true
                }
                if (cameraGesture && event.pointerCount == 1 && !diggingGesture) {
                    val dx = event.x - panLastX
                    val dy = event.y - panLastY
                    panLastX = event.x
                    panLastY = event.y
                    if (cameraZoom > 1.02f || abs(dx) + abs(dy) > dp(2f)) {
                        cameraPanX += dx
                        cameraPanY += dy
                        applyCameraTransform()
                        if (abs(dx) + abs(dy) > dp(4f)) suppressGridTap = true
                    }
                    return true
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (pinchActive) {
                    pinchActive = false
                    if (event.pointerCount >= 2) {
                        val idx = if (event.actionIndex == 0) 1 else 0
                        if (idx < event.pointerCount) {
                            panLastX = event.getX(idx)
                            panLastY = event.getY(idx)
                        }
                        cameraGesture = true
                    }
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val consumed = (cameraGesture || pinchActive) && suppressGridTap
                pinchActive = false
                cameraGesture = false
                if (action == MotionEvent.ACTION_CANCEL) suppressGridTap = false
                if (consumed) return true
            }
        }
        return false
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
