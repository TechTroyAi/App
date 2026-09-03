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
import android.text.InputType
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
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

/** Identifies the currently held title control so sprite buttons visibly depress on touch. */
private enum class TitleMenuAction { NONE, PLAY, CONTINUE, CHALLENGE, SOUND }

/** Shared visual roles for the reusable Fantasy Machinery in-game button family. */
private enum class UiControlTone { PRIMARY, SECONDARY, ACCENT, WARNING }

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
        private const val AUTO_NEXT_WAVE_DELAY = 60f
        private const val MAX_SEED_CHARACTERS = 12
        private const val DEFAULT_CUSTOM_SEED = "733101"
        private const val MAX_BLOCK_GENERATORS = 5
        private const val ELITE_TRAP_BREAK_DELAY = 1.75f
        /** Every category has this many slots before an Inventory Depot is online. */
        private const val DEFAULT_INVENTORY_CAPACITY = 25
        private const val MAX_INVENTORY_CAPACITY = 45
        private const val INVENTORY_PAGE_SIZE = 5
        private const val BUILD_SHELF_SLIDE_DURATION = 0.30f
        /** v1.4.4 Forge Overdrive — meter fills from enemy kills, boosts all towers briefly. */
        private const val OVERDRIVE_MAX = 100f
        private const val OVERDRIVE_DURATION = 8.0f
        private const val OVERDRIVE_FIRE_RATE_BOOST = 0.55f
        private const val OVERDRIVE_DAMAGE_BOOST = 1.25f
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
    private var selectedInventorySelection: InventorySelection? = null
    private var evolutionTower: Tower? = null
    private var imbuementTower: Tower? = null
    private var imbuementTrap: SpikeTrap? = null
    private var imbuementUtility: Utility? = null
    private var buildPage = BuildPage.TOWERS
    private var utilityPageIndex = 0
    private var towerPageIndex = 0
    private var inventoryPageIndex = 0
    private var inventoryCategory = InventoryCategory.TOWERS
    /** 0 is fully shown; 1 translates just the placement shelf below the viewport. */
    private var buildShelfSlide = 0f
    private var workshopTab = WorkshopTab.CRAFT
    private var workshopPageIndex = 0

    private val pathCells = ArrayList<GridCell>()
    private val reforgeOriginalPath = ArrayList<GridCell>()
    private val towers = ArrayList<Tower>()
    private val traps = ArrayList<SpikeTrap>()
    private val utilities = ArrayList<Utility>()
    private val storedTowers = ArrayList<StoredTower>()
    private val storedTraps = ArrayList<StoredTrap>()
    private val storedStructures = ArrayList<StoredStructure>()
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
    /** Waves launched but not fully cleared; multiple entries are allowed when waves are stacked. */
    private val activeWaveNumbers = LinkedHashSet<Int>()
    /** Milestone perk drafts waiting until all stacked waves have been cleared. */
    private val pendingPerkWaves = ArrayList<Int>()

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
    private var lastClearedWave = 0
    private var spawnIndex = 0
    private var spawnTimer = 0f
    /** Build-phase countdown before the next wave starts automatically. */
    private var nextWaveTimer = -1f
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

    /** v1.4.4 Forge Overdrive — fills on kills, activated by the player for a timed boost. */
    private var overdriveCharge = 0f
    private var overdriveActive = false
    private var overdriveTimer = 0f
    private var overdriveFlash = 0f
    /** v1.4.4 Wave Momentum — tracks core health at wave start for perfect-clear bonus. */
    private var livesAtWaveStart = STARTING_CORE
    private var perfectWaveStreak = 0

    /** Crafts C1: brief Hex immunity per tower cell key. */
    private var coolingImmunity = HashMap<Int, Float>()
    private var reforgeCost = 0
    private var gameMode = GameMode.ENDLESS
    private var challengeModifier = ChallengeModifier.NONE
    private var runSeed = 7331L
    private var customSeedText = try {
        preferences.getString("custom_seed_text", DEFAULT_CUSTOM_SEED).orEmpty()
            .filter { it in '0'..'9' }.take(MAX_SEED_CHARACTERS)
            .ifEmpty { DEFAULT_CUSTOM_SEED }
    } catch (_: Exception) {
        DEFAULT_CUSTOM_SEED
    }
    private var seedInputActive = false
    private var seedInputReplaceOnNextInput = false
    private var seedComposingText = ""
    private var feedbackEnabled = prefBoolean("feedback_enabled", true)
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
    private var titlePressedAction = TitleMenuAction.NONE
    /** Last live pointer position lets every art-backed in-game control use its pressed sprite. */
    private var uiTouchActive = false
    private var uiTouchX = -1f
    private var uiTouchY = -1f

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
    private val feedbackToggleRect = RectF()
    private val titlePanelRect = RectF()
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
    private val inventoryPageRect = RectF()
    private val challengeDailyRect = RectF()
    private val challengeSeedStartRect = RectF()
    private val challengeBackRect = RectF()
    private val seedInputRect = RectF()
    private val perkRects = ArrayList<RectF>()
    private val evolutionRects = ArrayList<RectF>()
    private val toolRects = ArrayList<Pair<BuildTool, RectF>>()
    private val utilityRects = ArrayList<Pair<UtilityKind, RectF>>()
    private val inventoryRects = ArrayList<Pair<InventorySelection, RectF>>()
    /** Forge Workshop gets its own half-action so it can be opened and stored independently. */
    private val workshopRect = RectF()
    private val structureStoreRect = RectF()
    private val workshopTabRects = ArrayList<Pair<WorkshopTab, RectF>>()
    private val workshopCardRects = ArrayList<RectF>()
    private val workshopBackRect = RectF()
    private val workshopPreviousRect = RectF()
    private val workshopNextRect = RectF()
    /** v1.4.4 Overdrive activation button — sits between resource stats and primary action. */
    private val overdriveRect = RectF()

    /**
     * The game is a custom SurfaceView, so the challenge seed uses a lightweight input connection
     * instead of adding an EditText over the renderer. This lets the normal Android keyboard paste
     * and type digits while keeping the visual field in the same Canvas UI.
     */
    private val seedInputConnection = object : BaseInputConnection(this, true) {
        override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
            commitSeedInput(text.toString())
            return true
        }

        override fun setComposingText(text: CharSequence, newCursorPosition: Int): Boolean {
            updateSeedComposition(text.toString())
            return true
        }

        override fun finishComposingText(): Boolean {
            seedComposingText = ""
            return true
        }

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            deleteSeedInput(beforeLength.coerceAtLeast(1))
            return true
        }

        override fun sendKeyEvent(event: KeyEvent): Boolean {
            if (event.action != KeyEvent.ACTION_DOWN) return true
            when (event.keyCode) {
                KeyEvent.KEYCODE_DEL -> deleteSeedInput(1)
                KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> startCustomChallenge()
                else -> {
                    val character = event.unicodeChar
                    if (character in '0'.code..'9'.code) commitSeedInput(character.toChar().toString())
                }
            }
            return true
        }

        override fun performEditorAction(actionCode: Int): Boolean {
            if (actionCode == EditorInfo.IME_ACTION_DONE || actionCode == EditorInfo.IME_ACTION_UNSPECIFIED) startCustomChallenge()
            return true
        }

        override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence = customSeedText.takeLast(n.coerceAtLeast(0))

        override fun getTextAfterCursor(n: Int, flags: Int): CharSequence = ""
    }

    init {
        holder.addCallback(this)
        isFocusable = true
        isFocusableInTouchMode = true
        keepScreenOn = true
        strokePaint.style = Paint.Style.STROKE
        pathCells.add(GridCell(0, START_ROW))
    }

    override fun onCheckIsTextEditor(): Boolean = seedInputActive

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        if (!seedInputActive) return null
        outAttrs.inputType = InputType.TYPE_CLASS_NUMBER
        outAttrs.imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        return seedInputConnection
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
                GamePhase.CHALLENGE_MENU -> {
                    endSeedInput()
                    phase = GamePhase.TITLE
                }
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
        feedbackToggleRect.set(soundRect.left - dp(7f) - smallButton, top, soundRect.left - dp(7f), bottom)
        // Compact landscape devices reserve the right-side actions first and collapse their
        // wording to icons/short labels, leaving a useful strip for the resource readouts.
        val compactTopControls = viewWidth < dp(480f)
        val actionWidth = if (compactTopControls) min(dp(96f), viewWidth * 0.16f) else min(dp(130f), viewWidth * 0.17f)
        primaryActionRect.set(soundRect.left - dp(8f) - actionWidth, top, soundRect.left - dp(8f), bottom)
        val resetWidth = if (compactTopControls) smallButton else min(dp(96f), viewWidth * 0.13f)
        resetPathRect.set(primaryActionRect.left - dp(7f) - resetWidth, top, primaryActionRect.left - dp(7f), bottom)
        // v1.4.4: Forge Overdrive button sits between the reset/reforge control and the primary
        // action, so it's reachable without leaving the command cluster during combat.
        val overdriveWidth = if (compactTopControls) smallButton else min(dp(72f), viewWidth * 0.10f)
        overdriveRect.set(resetPathRect.left - dp(7f) - overdriveWidth, top, resetPathRect.left - dp(7f), bottom)

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
        inventoryPageRect.set(utilityPageRect.right + pageGap, trapPageRect.bottom + pageGap, left + pageWidth, toolBottom)
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
        val storeSplitGap = dp(3f)
        val storeSplit = storeRect.centerX()
        workshopRect.set(storeRect.left, storeRect.top, storeSplit - storeSplitGap, storeRect.bottom)
        structureStoreRect.set(storeSplit + storeSplitGap, storeRect.top, storeRect.right, storeRect.bottom)
        imbueRect.set(actionLeft, storeRect.bottom + panelGap * 0.5f, panelLeft + panelWidth, storeRect.bottom + panelGap * 0.5f + actionHeight)
        sellRect.set(actionLeft, imbueRect.bottom + panelGap * 0.5f, panelLeft + panelWidth, panelBottom)

        // Title screen: keep one focused interaction column. The panel and every control are
        // raster sprites, while these rectangles only define responsive placement and hit areas.
        val maxTitlePanelWidth = min(viewWidth * 0.46f, dp(440f))
        val maxTitlePanelHeight = min(viewHeight * 0.70f, max(1f, viewHeight - dp(8f)))
        // Fit the authored 760:632 panel uniformly so a short landscape display never stretches
        // the artwork vertically or leaves less room than its control column needs.
        val titlePanelScale = min(maxTitlePanelWidth / 760f, maxTitlePanelHeight / 632f)
        val titlePanelWidth = 760f * titlePanelScale
        val titlePanelHeight = 632f * titlePanelScale
        val preferredTitlePanelTop = max(dp(72f), viewHeight * 0.29f)
        val titlePanelTop = min(preferredTitlePanelTop, max(0f, viewHeight - dp(8f) - titlePanelHeight))
        titlePanelRect.set(
            viewWidth * 0.5f - titlePanelWidth * 0.5f,
            titlePanelTop,
            viewWidth * 0.5f + titlePanelWidth * 0.5f,
            titlePanelTop + titlePanelHeight
        )
        val desiredTitleButtonWidth = titlePanelRect.width() * 0.80f
        val titleButtonGap = min(dp(10f), titlePanelRect.height() * 0.035f)
        val titleButtonsTopInset = min(dp(50f), titlePanelRect.height() * 0.13f)
        val titleButtonsBottomInset = min(dp(24f), titlePanelRect.height() * 0.07f)
        val titleButtonLaneHeight = max(1f, titlePanelRect.height() - titleButtonsTopInset - titleButtonsBottomInset)
        // Preserve the authored button-sprite aspect ratio and reduce the whole button scale on
        // short landscape displays instead of letting the third action escape its panel.
        val titleButtonHeight = min(
            desiredTitleButtonWidth * 152f / 640f,
            max(1f, (titleButtonLaneHeight - titleButtonGap * 2f) / 3f)
        )
        val titleButtonWidth = titleButtonHeight * 640f / 152f
        val titleButtonsHeight = titleButtonHeight * 3f + titleButtonGap * 2f
        val titleButtonsTop = titlePanelRect.top + titleButtonsTopInset + (titleButtonLaneHeight - titleButtonsHeight) * 0.5f
        val titleButtonLeft = titlePanelRect.centerX() - titleButtonWidth * 0.5f
        titlePlayRect.set(titleButtonLeft, titleButtonsTop, titleButtonLeft + titleButtonWidth, titleButtonsTop + titleButtonHeight)
        titleContinueRect.set(titleButtonLeft, titlePlayRect.bottom + titleButtonGap, titleButtonLeft + titleButtonWidth, titlePlayRect.bottom + titleButtonGap + titleButtonHeight)
        titleChallengeRect.set(titleButtonLeft, titleContinueRect.bottom + titleButtonGap, titleButtonLeft + titleButtonWidth, titleContinueRect.bottom + titleButtonGap + titleButtonHeight)
        val titleSoundSize = min(dp(58f), min(viewWidth, viewHeight) * 0.105f)
        titleSoundRect.set(viewWidth - dp(16f) - titleSoundSize, dp(16f), viewWidth - dp(16f), dp(16f) + titleSoundSize)

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
        val seedFieldWidth = min(dp(420f), viewWidth * 0.58f)
        val seedFieldHeight = min(dp(52f), viewHeight * 0.105f)
        seedInputRect.set(
            viewWidth * 0.5f - seedFieldWidth * 0.5f,
            viewHeight * 0.63f,
            viewWidth * 0.5f + seedFieldWidth * 0.5f,
            viewHeight * 0.63f + seedFieldHeight
        )

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

    private fun rebuildToolRects(left: Float = inventoryPageRect.right + dp(7f), width: Float = min(viewWidth - dp(20f), dp(790f)) - (inventoryPageRect.right - towerPageRect.left) - dp(7f), top: Float = viewHeight - bottomBarHeight + dp(9f), bottom: Float = viewHeight - dp(9f)) {
        toolRects.clear()
        utilityRects.clear()
        inventoryRects.clear()
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
            BuildPage.STRUCTURES -> {
                val kinds = UtilityKind.values().drop(utilityPageIndex * 4).take(4)
                val buttonWidth = (width - gap * (kinds.size - 1)) / max(1, kinds.size)
                var x = left
                for (kind in kinds) {
                    utilityRects.add(Pair(kind, RectF(x, top, x + buttonWidth, bottom)))
                    x += buttonWidth + gap
                }
            }
            BuildPage.INVENTORY -> {
                val start = inventoryPageIndex * INVENTORY_PAGE_SIZE
                val indices = (start until min(inventoryCount(inventoryCategory), start + INVENTORY_PAGE_SIZE)).toList()
                val count = max(1, indices.size)
                val buttonWidth = (width - gap * (count - 1)) / count
                var x = left
                for (index in indices) {
                    val selection = InventorySelection(inventoryCategory, index)
                    inventoryRects.add(Pair(selection, RectF(x, top, x + buttonWidth, bottom)))
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
        // v1.4.4 Forge Overdrive — tick the active boost and its flash
        if (overdriveActive) {
            overdriveTimer = max(0f, overdriveTimer - delta)
            overdriveFlash = max(0f, overdriveFlash - delta * 1.5f)
            if (overdriveTimer <= 0f) {
                overdriveActive = false
                overdriveTimer = 0f
                // v1.4.4 Overdrive Resonance — all towers fire a bonus shot as the boost ends
                for (tower in towers) {
                    if (tower.disabledTimer > 0f) continue
                    val target = findTarget(tower)
                    if (target != null) {
                        tower.cooldown = 0f
                        burst(tower.col + 0.5f, tower.row + 0.5f, Color.rgb(255, 215, 80), 4, 0.5f)
                    }
                }
                setBanner("OVERDRIVE RESONANCE  •  BONUS VOLLEY", 1.8f)
                audio.play("build", 0.50f, 1.40f)
            }
        }
        if (phase == GamePhase.BUILD && nextWaveTimer >= 0f && pendingPerkWaves.isEmpty()) {
            nextWaveTimer = max(0f, nextWaveTimer - delta)
            if (nextWaveTimer <= 0f) startWave()
        }
        if (phase == GamePhase.WAVE) updateWave(delta)
        updateBuildShelfSlide(delta)
        updateEffects(delta)
    }

    /** The build shelf, not inspection panels, leaves the screen during combat. */
    private fun updateBuildShelfSlide(delta: Float) {
        val combatHidden = phase == GamePhase.WAVE || (phase == GamePhase.PAUSED && phaseBeforePause == GamePhase.WAVE)
        val target = if (combatHidden) 1f else 0f
        val step = delta / BUILD_SHELF_SLIDE_DURATION
        buildShelfSlide = if (target > buildShelfSlide) min(target, buildShelfSlide + step) else max(target, buildShelfSlide - step)
    }

    private fun buildShelfOffsetY(): Float = buildShelfSlide * (bottomBarHeight + dp(22f))

    /** Prevent a tap from hitting a static rectangle while its card is still moving into view. */
    private fun buildShelfReadyForInput(): Boolean = buildShelfSlide <= 0.02f

    private fun updateWave(delta: Float) {
        if (spawnIndex < waveQueue.size) {
            spawnTimer -= delta
            if (spawnTimer <= 0f) {
                val spec = waveQueue[spawnIndex]
                spawnEnemy(spec)
                spawnIndex += 1
                val sourceWave = if (spec.sourceWave > 0) spec.sourceWave else waveNumber
                val rush = sourceWave % 8 == 2 || sourceWave % 8 == 1 || challengeModifier == ChallengeModifier.RUSH_HOUR
                // Denser deployment keeps later waves threatening and makes stacked waves feel
                // like a real pressure system instead of a second queue sitting idle.
                spawnTimer = if (rush) 0.32f else max(0.34f, 0.72f - sourceWave * 0.007f)
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
            val enemyWave = if (enemy.sourceWave > 0) enemy.sourceWave else waveNumber
            val regeneration = enemy.kind.regeneration + if (enemyWave % 8 == 4) 0.006f else 0f
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
            val progressBeforeMove = enemy.progress
            if (enemy.stunTimer <= 0f) enemy.progress += enemy.moveSpeed * slowMultiplier * gateSlow * wardenSlow * delta
            updateEnemyPosition(enemy)
            if (enemy.kind.elite || enemy.kind.boss) {
                val advanced = enemy.progress > progressBeforeMove + 0.0001f
                enemy.stuckTimer = if (advanced) 0f else enemy.stuckTimer + delta
            }
            applyCorruptionToEnemy(enemy, delta)
            triggerTrapIfNeeded(enemy)
            if (!enemy.alive || enemy.dying) continue
            breakTrapUnderStalledEnemy(enemy)

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
                    // v1.4.4 Forge Overdrive — all towers fire faster while active
                    if (overdriveActive) interval *= OVERDRIVE_FIRE_RATE_BOOST
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
        if (phase == GamePhase.WAVE) processClearedWaves()
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
                        val minion = Enemy(nextEnemyId++, EnemyKind.SPLITLING, enemy.healthScale * 0.11f, min(1.55f, enemy.speedScale * 1.18f), 0.45f, splitDepth = 1, sourceWave = enemy.sourceWave)
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
                    val minion = Enemy(nextEnemyId++, EnemyKind.MYCELIAL, enemy.healthScale * 0.14f, min(1.4f, enemy.speedScale * 1.1f), 0.4f, splitDepth = 1, sourceWave = enemy.sourceWave)
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
        val enemy = Enemy(
            nextEnemyId++,
            spec.kind,
            spec.healthScale,
            spec.speedScale * challengeSpeed,
            spec.rewardScale,
            spec.bossTier,
            spec.splitDepth,
            spec.sourceWave
        )
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
        var destroyAfterTrigger: SpikeTrap? = null
        for (trap in traps) {
            if (trap.col != cell.col || trap.row != cell.row) continue
            if (trap.jamTimer > 0f) continue
            val beaconRelay = isNearTowerKind(trap.col, trap.row, TowerKind.BEACON)
            val maxTriggers = 1 + perkCount(ForgePerk.DOUBLE_TRIGGER) + if (beaconRelay) 1 else 0
            val triggers = enemy.trapTriggerCounts[trap.id] ?: 0
            if (triggers >= maxTriggers) continue
            // F1 Sapper: after 2 sabotaged traps this life, skip further traps
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
            // Sappers use the trap as a breach point, then remove it. Defer the removal until
            // after the loop so the ArrayList is never mutated while it is being iterated.
            if (enemy.kind == EnemyKind.SAPPER && enemy.targetable) destroyAfterTrigger = trap
        }
        destroyAfterTrigger?.let { trap ->
            destroyTrap(
                trap,
                "SAPPER SABOTAGED  ${trap.kind.title.uppercase()}",
                EnemyKind.SAPPER.color
            )
        }
    }

    private fun breakTrapUnderStalledEnemy(enemy: Enemy) {
        if ((!enemy.kind.elite && !enemy.kind.boss) || enemy.stuckTimer < ELITE_TRAP_BREAK_DELAY) return
        val pathIndex = min(max(0, (enemy.progress + 0.25f).toInt()), pathCells.size - 1)
        val cell = pathCells[pathIndex]
        val trap = traps.firstOrNull { it.col == cell.col && it.row == cell.row } ?: return
        val title = if (enemy.kind.boss) "BOSS" else "ELITE"
        destroyTrap(trap, "$title  ${enemy.kind.title.uppercase()} BROKE ${trap.kind.title.uppercase()}", enemy.kind.color)
        enemy.stuckTimer = 0f
    }

    private fun destroyTrap(trap: SpikeTrap, message: String, effectColor: Int) {
        if (!traps.remove(trap)) return
        if (selectedTrap === trap) selectedTrap = null
        floatingLabels.add(FloatingLabel("TRAP BROKEN", trap.col + 0.5f, trap.row + 0.18f, effectColor, life = 0.9f, pop = 1.3f))
        burst(trap.col + 0.5f, trap.row + 0.5f, effectColor, 16, 1.0f)
        setBanner(message, 1.8f)
        audio.play("dig", 0.42f, 0.62f)
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
        // v1.4.4 Forge Overdrive — bonus damage while active
        if (overdriveActive) damage *= OVERDRIVE_DAMAGE_BOOST
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
            // v1.4.4 Forge Overdrive — kills charge the overdrive meter (elites/bosses give more)
            if (!overdriveActive) {
                val chargeGain = when {
                    enemy.kind.boss -> 35f
                    enemy.kind.elite -> 15f
                    else -> 4f
                }
                overdriveCharge = min(OVERDRIVE_MAX, overdriveCharge + chargeGain)
            }
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
                    val child = Enemy(nextEnemyId++, EnemyKind.MOSSER, enemy.healthScale * 0.34f, min(1.7f, enemy.speedScale * 1.3f), 0.32f, splitDepth = 1, sourceWave = enemy.sourceWave)
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

    /**
     * Resolve wave ownership independently of the flat spawn queue. A player can launch wave N+1
     * while N is still alive, so the queue and the enemy list may contain several waves at once.
     */
    private fun processClearedWaves() {
        if (phase != GamePhase.WAVE) return
        val clearedWaves = activeWaveNumbers.filter { sourceWave ->
            val lastQueuedIndex = waveQueue.indexOfLast { it.sourceWave == sourceWave }
            lastQueuedIndex >= 0 &&
                spawnIndex > lastQueuedIndex &&
                enemies.none { it.sourceWave == sourceWave } &&
                pendingSpawns.none { it.sourceWave == sourceWave }
        }
        for (sourceWave in clearedWaves) completeWave(sourceWave)

        if (phase == GamePhase.WAVE && activeWaveNumbers.isEmpty() &&
            spawnIndex >= waveQueue.size && enemies.isEmpty() &&
            pendingSpawns.isEmpty() && projectiles.isEmpty()
        ) {
            finishWaveStack()
        }
    }

    private fun completeWave(completedWave: Int) {
        if (!activeWaveNumbers.remove(completedWave)) return
        lastClearedWave = max(lastClearedWave, completedWave)

        var reward = min(250_000L, 45L + completedWave.toLong() * 13L).toInt()
        reward = (reward * (1f + perkCount(ForgePerk.COMPOUND_BLOCKS) * 0.20f)).toInt()
        if (perkCount(ForgePerk.LONG_ROAD_DIVIDEND) > 0) {
            reward = safeAdd(reward, max(0, pathCells.size - 12) * 3 * perkCount(ForgePerk.LONG_ROAD_DIVIDEND))
        }
        val utilityIncome = processUtilityWaveClear()
        gold = safeAdd(gold, safeAdd(reward, utilityIncome))
        score = safeAdd(score, reward * 5 + utilityIncome * 3)
        // v1.4.4 Wave Momentum — perfect wave (no core damage) charges overdrive + streak bonus
        if (lives >= livesAtWaveStart && !overdriveActive) {
            perfectWaveStreak += 1
            val momentumBonus = 8f + min(12f, perfectWaveStreak * 2f)
            overdriveCharge = min(OVERDRIVE_MAX, overdriveCharge + momentumBonus)
            if (perfectWaveStreak >= 3) {
                floatingLabels.add(FloatingLabel("MOMENTUM ×$perfectWaveStreak", COLS * 0.5f, ROWS * 0.5f, Color.rgb(255, 215, 80), life = 1.4f, pop = 1.4f))
            }
        } else {
            perfectWaveStreak = 0
        }
        if (surveyLensWaves > 0) surveyLensWaves -= 1
        if (completedWave % 5 == 0 && perkCount(ForgePerk.CORE_REGENERATION) > 0) {
            lives = min(maxCore, lives + perkCount(ForgePerk.CORE_REGENERATION))
        }
        if (completedWave % 10 == 0) {
            val tier = completedWave / 10
            lives = min(maxCore, lives + 1)
            forgeCharges = safeAdd(forgeCharges, 6 + tier * 2 + perkCount(ForgePerk.FORGE_MASTERY) * 2 + perkCount(ForgePerk.BOSS_HARVEST) * 3)
            evolutionCores = safeAdd(evolutionCores, 1 + perkCount(ForgePerk.BOSS_HARVEST))
            spreadBossCorruption(tier, completedWave)
            activateBossCycleUtilities()
            setBanner("BOSS BROKEN  +${safeAdd(reward, utilityIncome)} BLOCKS  +${6 + tier * 2} FORGE", 3.0f)
        } else {
            setBanner("WAVE $completedWave CLEARED  +${safeAdd(reward, utilityIncome)} BLOCKS", 2.4f)
        }
        if (routeOilWaves > 0) routeOilWaves -= 1
        if (completedWave % 5 == 0) pendingPerkWaves.add(completedWave)

        selectedTower = null
        selectedTrap = null
        selectedUtility = null
        selectedCorruption = null
        updateRecords(completedWave)
        audio.play("build", 0.45f, 1.14f)

        if (activeWaveNumbers.isNotEmpty()) {
            setBanner("WAVE $completedWave CLEARED  •  ${activeWaveNumbers.size} WAVE(S) STILL LIVE", 2.2f)
        }
    }

    private fun finishWaveStack() {
        if (phase != GamePhase.WAVE || activeWaveNumbers.isNotEmpty()) return
        // Do not retain every completed wave's spawn specs during an endless run.
        waveQueue.clear()
        spawnIndex = 0
        spawnTimer = 0f

        if (pendingPerkWaves.isNotEmpty()) {
            val draftWave = pendingPerkWaves.removeAt(0)
            generatePerkChoices(draftWave)
            nextWaveTimer = -1f
            phase = GamePhase.PERK_DRAFT
            setBanner("WAVE $draftWave REWARD READY  •  CHOOSE YOUR FORGE PERK", 2.8f)
            saveRun()
        } else {
            phase = GamePhase.BUILD
            nextWaveTimer = AUTO_NEXT_WAVE_DELAY
            setBanner("WAVE $lastClearedWave CLEARED  •  NEXT WAVE IN ${countdownLabel()}", 2.8f)
            saveRun()
        }
    }

    private fun processUtilityWaveClear(): Int {
        var income = 0
        for (utility in utilities) {
            if (utility.disabledTimer > 0f) continue
            when (utility.kind) {
                UtilityKind.BLOCK_GENERATOR -> {
                    val base = utility.blockOutput()
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

    private fun generatePerkChoices(forWave: Int) {
        perkChoices.clear()
        val pool = ForgePerk.values().toMutableList()
        val choiceRandom = Random(runSeed xor (forWave.toLong() * 0x5DEECE66DL))
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
        perkChoices.clear()
        if (pendingPerkWaves.isNotEmpty()) {
            val draftWave = pendingPerkWaves.removeAt(0)
            generatePerkChoices(draftWave)
            nextWaveTimer = -1f
            phase = GamePhase.PERK_DRAFT
            setBanner("WAVE $draftWave REWARD READY  •  CHOOSE YOUR FORGE PERK", 2.8f)
        } else {
            phase = GamePhase.BUILD
            nextWaveTimer = AUTO_NEXT_WAVE_DELAY
            setBanner("FORGE PERK  ${perk.title.uppercase()}  •  NEXT WAVE IN ${countdownLabel()}", 2.8f)
        }
        saveRun()
        audio.play("build", 0.60f, 1.22f)
    }

    private fun spreadBossCorruption(tier: Int, sourceWave: Int) {
        var count = min(6, 2 + tier)
        if (challengeModifier == ChallengeModifier.DOUBLE_CORRUPTION) count *= 2
        count = max(1, count - perkCount(ForgePerk.CORRUPTION_WARD))
        val corruptionRandom = Random(runSeed xor (sourceWave.toLong() * 7919L))
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
        activeWaveNumbers.clear()
        pendingPerkWaves.clear()
        waveQueue.clear()
        spawnIndex = 0
        nextWaveTimer = -1f
        // v1.4.4: reset overdrive state on run end
        overdriveCharge = 0f
        overdriveActive = false
        overdriveTimer = 0f
        overdriveFlash = 0f
        perfectWaveStreak = 0
        livesAtWaveStart = STARTING_CORE
        selectedTower = null
        selectedTrap = null
        selectedUtility = null
        selectedCorruption = null
        updateRecords()
        clearSavedRun()
    }

    private fun updateRecords(clearedWave: Int = waveNumber) {
        val editor = preferences.edit()
        if (gameMode == GameMode.ENDLESS) {
            if (score > bestScore) { bestScore = score; editor.putInt("best_score", bestScore) }
            if (clearedWave > bestWave) { bestWave = clearedWave; editor.putInt("best_wave", bestWave) }
        } else if (gameMode == GameMode.DAILY) {
            if (score > bestDailyScore) { bestDailyScore = score; editor.putInt("best_daily_score", bestDailyScore) }
            if (clearedWave > bestDailyWave) { bestDailyWave = clearedWave; editor.putInt("best_daily_wave", bestDailyWave) }
        } else {
            if (score > bestCustomScore) { bestCustomScore = score; editor.putInt("best_custom_score", bestCustomScore) }
            if (clearedWave > bestCustomWave) { bestCustomWave = clearedWave; editor.putInt("best_custom_wave", bestCustomWave) }
        }
        editor.apply()
    }

    /** v1.4.4: Forge Overdrive — player-activated burst that speeds all towers and boosts damage. */
    private fun activateOverdrive() {
        if (overdriveActive || overdriveCharge < OVERDRIVE_MAX) return
        if (phase != GamePhase.WAVE && phase != GamePhase.BUILD) return
        overdriveActive = true
        overdriveTimer = OVERDRIVE_DURATION
        overdriveCharge = 0f
        overdriveFlash = 1f
        setBanner("FORGE OVERDRIVE  •  ${OVERDRIVE_DURATION.toInt()}S BOOST", 2.2f)
        audio.play("build", 0.70f, 1.30f)
        screenShake = max(screenShake, 0.25f)
        // Visual burst: particles from every tower + screen-wide gold flash
        for (tower in towers) {
            burst(tower.col + 0.5f, tower.row + 0.5f, Color.rgb(255, 200, 60), 8, 0.8f)
            burst(tower.col + 0.5f, tower.row + 0.5f, Color.rgb(255, 255, 180), 4, 0.5f)
        }
        // Bonus: wave momentum streak adds to overdrive duration
        if (perfectWaveStreak >= 3) {
            val bonusDuration = min(4f, perfectWaveStreak * 0.5f)
            overdriveTimer += bonusDuration
            setBanner("FORGE OVERDRIVE  •  ${overdriveTimer.toInt()}S  MOMENTUM ×$perfectWaveStreak", 2.4f)
        }
    }

    private fun startWave() {
        if (phase != GamePhase.BUILD || !pathComplete) return
        saveRun()
        launchWave(waveNumber + 1, stacked = false)
    }

    /** Launch another wave without stopping the current wave; this is the manual stack action. */
    private fun stackNextWave() {
        if (phase != GamePhase.WAVE || !pathComplete || activeWaveNumbers.isEmpty()) return
        launchWave(waveNumber + 1, stacked = true)
    }

    private fun launchWave(nextWave: Int, stacked: Boolean) {
        if (!stacked) {
            activeWaveNumbers.clear()
            waveQueue.clear()
            spawnIndex = 0
            spawnTimer = 0f
        }
        nextWaveTimer = -1f
        waveNumber = nextWave
        buildWaveQueue(nextWave)
        if (!stacked) spawnTimer = 0.25f else if (spawnTimer <= 0f) spawnTimer = 0.05f
        activeWaveNumbers.add(nextWave)
        selectedTower = null
        selectedTrap = null
        selectedCorruption = null
        phase = GamePhase.WAVE
        // v1.4.4 Wave Momentum — snapshot core health for perfect-clear overdrive bonus
        livesAtWaveStart = lives
        for (tower in towers) if (tower.imbuement == Imbuement.SURGE) tower.surgeCharges = 3
        for (trap in traps) if (trap.imbuement == Imbuement.SURGE) trap.surgeCharges = 3
        val message = when {
            nextWave % 10 == 0 -> {
                val tier = nextWave / 10
                val bossName = when {
                    nextWave % 30 == 0 -> "MUTATED OVERGROWTH"
                    tier % 5 == 1 -> "IRON MONARCH"
                    tier % 5 == 2 -> "SPORE SOVEREIGN"
                    tier % 5 == 3 -> "TIDAL ROOT"
                    tier % 5 == 4 -> "ASHEN CHOIR"
                    else -> "MUTATED OVERGROWTH"
                }
                "$bossName  TIER $tier"
            }
            nextWave % 5 == 0 -> "ELITE SIGNAL  $waveTheme"
            else -> "WAVE $nextWave  $waveTheme"
        }
        setBanner(if (stacked) "STACKED  $message" else message, 2.4f)
        audio.play("wave", 0.65f, if (nextWave % 10 == 0) 0.78f else 1f)
    }

    private fun buildWaveQueue(wave: Int) {
        // v1.4.2 pressure pass: more bodies, a steeper health curve, faster deployment, and
        // earlier second elites. This keeps the first several loops from being free clears while
        // still rewarding the player with a matching economy curve.
        val healthScale = waveHealthScale(wave)
        val speedScale = min(1.62f, 1f + wave * 0.0085f)
        val rewardScale = min(20f, 1f + wave * 0.085f)
        val regularCount = min(54, 8 + (wave * 0.65f).toInt())
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
                EnemyKind.SAPPER -> 1.06f
                EnemyKind.RUNNER, EnemyKind.NEEDLEFLY, EnemyKind.BRIAR_MITE, EnemyKind.DRIFT_SEED -> 1.03f
                else -> 1f
            }
            waveQueue.add(SpawnSpec(kind, healthScale, speedScale * speedMul, rewardScale, sourceWave = wave))
        }
        if (wave % 8 == 5 && wave % 5 != 0) waveQueue.add(min(waveQueue.size, waveQueue.size * 2 / 3), SpawnSpec(EnemyKind.HEX_WEAVER, healthScale * 0.34f, speedScale, rewardScale * 0.55f, sourceWave = wave))
        if (wave % 5 == 0) {
            val elites = arrayOf(
                EnemyKind.IRONHIDE, EnemyKind.BLINK_STALKER, EnemyKind.ROOTCALLER, EnemyKind.HEX_WEAVER,
                EnemyKind.SIEGE_COLOSSUS, EnemyKind.THORNBACK, EnemyKind.GRAVE_MENDER, EnemyKind.PYRE_WIGHT, EnemyKind.VEIN_LURKER, EnemyKind.MIRROR_MOTH
            )
            val eliteKind = elites[((wave / 5) - 1 + seedOffset) % elites.size]
            val insertAt = min(waveQueue.size, waveQueue.size * 2 / 3)
            waveQueue.add(insertAt, SpawnSpec(eliteKind, healthScale * 0.72f, speedScale, rewardScale * 1.1f, sourceWave = wave))
            if (wave >= 20) waveQueue.add(min(waveQueue.size, insertAt + 5), SpawnSpec(elites[((wave / 5) + 1 + seedOffset) % elites.size], healthScale * 0.58f, speedScale, rewardScale, sourceWave = wave))
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
            waveQueue.add(SpawnSpec(bossKind, healthScale * (0.90f + min(1.45f, tier * 0.065f)), min(1.36f, speedScale), rewardScale * 1.3f, tier, sourceWave = wave))
        }
        val broodCount = corruptions.count { it.kind == CorruptionKind.BROOD_NEST }
        repeat(min(18, broodCount)) { waveQueue.add(min(waveQueue.size, 2 + it), SpawnSpec(EnemyKind.SPLITLING, healthScale * 0.28f, speedScale * 1.12f, rewardScale * 0.25f, splitDepth = 1, sourceWave = wave)) }
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
        return min(1_000_000_000f, 1.095.pow(exponentialWave.toDouble()).toFloat() * (1f + min(100_000, tail) * 0.060f))
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

    /** Capacity applies independently to every inventory category, never as one shared pool. */
    private fun inventoryCapacity(): Int {
        val depot = utilities.filter { it.kind == UtilityKind.CACHE_DEPOT }.maxByOrNull { it.level }
        val bonus = if (depot == null) 0 else when (utilityPowerLevel(depot)) {
            1 -> 5
            2 -> 10
            3 -> 15
            else -> 20
        }
        return min(MAX_INVENTORY_CAPACITY, DEFAULT_INVENTORY_CAPACITY + bonus)
    }

    private fun inventoryCount(category: InventoryCategory): Int = when (category) {
        InventoryCategory.TOWERS -> storedTowers.size
        InventoryCategory.TRAPS -> storedTraps.size
        InventoryCategory.STRUCTURES -> storedStructures.size
    }

    private fun inventoryPageCount(category: InventoryCategory = inventoryCategory): Int =
        max(1, (inventoryCount(category) + INVENTORY_PAGE_SIZE - 1) / INVENTORY_PAGE_SIZE)

    private fun hasInventoryRoom(category: InventoryCategory): Boolean = inventoryCount(category) < inventoryCapacity()

    /** Removing the last depot may shrink every separate shelf back to its base capacity. */
    private fun lastDepotWouldOverfillInventory(structure: Utility, addingToStructureShelf: Boolean): Boolean {
        if (structure.kind != UtilityKind.CACHE_DEPOT || utilities.any { it !== structure && it.kind == UtilityKind.CACHE_DEPOT }) return false
        val projectedStructures = storedStructures.size + (if (addingToStructureShelf) 1 else 0)
        return storedTowers.size > DEFAULT_INVENTORY_CAPACITY ||
            storedTraps.size > DEFAULT_INVENTORY_CAPACITY ||
            projectedStructures > DEFAULT_INVENTORY_CAPACITY
    }

    private fun canStoreStructure(structure: Utility): Boolean =
        hasInventoryRoom(InventoryCategory.STRUCTURES) && !lastDepotWouldOverfillInventory(structure, addingToStructureShelf = true)

    private fun isValidInventorySelection(selection: InventorySelection?): Boolean =
        selection != null && selection.index in 0 until inventoryCount(selection.category)

    private fun inventoryItemTitle(selection: InventorySelection): String = when (selection.category) {
        InventoryCategory.TOWERS -> storedTowers.getOrNull(selection.index)?.kind?.title
        InventoryCategory.TRAPS -> storedTraps.getOrNull(selection.index)?.kind?.title
        InventoryCategory.STRUCTURES -> storedStructures.getOrNull(selection.index)?.kind?.title
    } ?: "UNAVAILABLE"

    private fun inventoryItemRank(selection: InventorySelection): String = when (selection.category) {
        InventoryCategory.TOWERS -> storedTowers.getOrNull(selection.index)?.rankLabel()
        InventoryCategory.TRAPS -> storedTraps.getOrNull(selection.index)?.rankLabel()
        InventoryCategory.STRUCTURES -> storedStructures.getOrNull(selection.index)?.rankLabel()
    } ?: ""

    private fun inventoryItemImbuement(selection: InventorySelection): Imbuement? = when (selection.category) {
        InventoryCategory.TOWERS -> storedTowers.getOrNull(selection.index)?.imbuement
        InventoryCategory.TRAPS -> storedTraps.getOrNull(selection.index)?.imbuement
        InventoryCategory.STRUCTURES -> storedStructures.getOrNull(selection.index)?.imbuement
    }

    private fun inventoryStorageDiscount(): Float {
        val depot = utilities.filter { it.kind == UtilityKind.CACHE_DEPOT }.maxByOrNull { it.level }
        if (depot == null) return 0f
        val conservation = if (depot.imbuement == Imbuement.CONSERVATION) 0.10f else 0f
        return min(0.55f, utilityPowerLevel(depot) * 0.10f + conservation)
    }

    private fun discountedStorageCost(base: Int, imbuement: Imbuement?): Int {
        var cost = (base * (1f - inventoryStorageDiscount())).toInt()
        if (imbuement == Imbuement.CONSERVATION) cost = (cost * 0.85f).toInt()
        return max(1, cost)
    }

    private fun towerStorageCost(tower: Tower): Int {
        val evolutionPremium = if (tower.evolution != null) 36 else 0
        val base = max(25, (tower.kind.cost * 0.30f).toInt() + (tower.level - 1) * 16 + tower.overcharge * 10 + evolutionPremium)
        return discountedStorageCost(base, tower.imbuement)
    }

    private fun trapStorageCost(trap: SpikeTrap): Int {
        val base = max(20, (trap.kind.cost * 0.30f).toInt() + (trap.level - 1) * 12 + trap.overcharge * 8)
        return discountedStorageCost(base, trap.imbuement)
    }

    private fun structureStorageCost(structure: Utility): Int {
        val base = max(25, (structure.kind.cost * 0.30f).toInt() + (structure.level - 1) * 15)
        return discountedStorageCost(base, structure.imbuement)
    }

    // There is intentionally no global Structure capacity. Free terrain and cost remain
    // meaningful limits; each kind is unique except for the five-Structure Block Generator cap.

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
        val towerData = towers.joinToString(";") { "${it.col},${it.row},${it.kind.name},${it.level},${it.overcharge},${it.evolution?.name ?: "NONE"},${it.imbuement?.name ?: "NONE"},${it.activationCount}" }
        val trapData = traps.joinToString(";") { "${it.id},${it.col},${it.row},${it.kind.name},${it.level},${it.overcharge},${it.imbuement?.name ?: "NONE"},${it.activationCount}" }
        val utilityData = utilities.joinToString(";") { "${it.col},${it.row},${it.kind.name},${it.level},${it.imbuement?.name ?: "NONE"},${it.productionProgress},${it.activationCount}" }
        val corruptionData = corruptions.joinToString(";") { "${it.id},${it.cell.col},${it.cell.row},${it.kind.name}" }
        val perkData = perks.entries.joinToString(";") { "${it.key.name},${it.value}" }
        val pendingPerkData = if (phase == GamePhase.PERK_DRAFT) perkChoices.joinToString(",") { it.name } else ""
        val pendingPerkWavesData = pendingPerkWaves.joinToString(",")
        val savedNextWaveTimer = if (phase == GamePhase.BUILD || (phase == GamePhase.PAUSED && phaseBeforePause == GamePhase.BUILD)) nextWaveTimer else -1f
        val towerInventoryData = storedTowers.joinToString(";") { "${it.kind.name},${it.level},${it.overcharge},${it.evolution?.name ?: "NONE"},${it.imbuement?.name ?: "NONE"},${it.activationCount}" }
        val trapInventoryData = storedTraps.joinToString(";") { "${it.kind.name},${it.level},${it.overcharge},${it.imbuement?.name ?: "NONE"},${it.activationCount}" }
        val structureInventoryData = storedStructures.joinToString(";") { "${it.kind.name},${it.level},${it.imbuement?.name ?: "NONE"},${it.productionProgress},${it.activationCount}" }
        val supplyData = supplies.entries.filter { it.value > 0 }.joinToString(";") { "${it.key.name},${it.value}" }
        preferences.edit()
            .putInt("run_save_version", 4)
            .putBoolean("has_saved_run", true)
            .putString("run_path", pathData)
            .putString("run_towers", towerData)
            .putString("run_traps", trapData)
            .putString("run_utilities", utilityData)
            .putString("run_corruptions", corruptionData)
            .putString("run_perks", perkData)
            .putString("run_pending_perks", pendingPerkData)
            .putString("run_pending_perk_waves", pendingPerkWavesData)
            .putFloat("run_next_wave_timer", savedNextWaveTimer)
            .putString("run_inventory_towers", towerInventoryData)
            .putString("run_inventory_traps", trapInventoryData)
            .putString("run_inventory_structures", structureInventoryData)
            // Keep the old trap-only key as a compatibility mirror for an interrupted upgrade.
            .putString("run_inventory", trapInventoryData)
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
                tower.evolution = tower.evolution?.takeIf { it.kind == tower.kind }
                tower.imbuement = if (values.size >= 7 && values[6] != "NONE") Imbuement.valueOf(values[6]) else null
                tower.activationCount = if (values.size >= 8) values[7].toInt().coerceIn(0, 2_000_000_000) else 0
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
                trap.activationCount = if (values.size >= 8) values[7].toInt().coerceIn(0, 2_000_000_000) else 0
                traps.add(trap)
            }
            utilities.clear()
            // Structures no longer have a four-Structure global cap. The board and placement
            // rules still protect the save from impossible coordinates/collisions.
            preferences.getString("run_utilities", "").orEmpty().split(';').filter { it.isNotBlank() }.take(COLS * ROWS).forEach {
                val values = it.split(',')
                val col = values[0].toInt()
                val row = values[1].toInt()
                if (col !in 0 until COLS || row !in 0 until ROWS) throw IllegalStateException("Invalid utility cell")
                val kind = UtilityKind.valueOf(values[2])
                if (kind == UtilityKind.BLOCK_GENERATOR && utilities.count { it.kind == kind } >= MAX_BLOCK_GENERATORS) return@forEach
                val utility = Utility(col, row, kind)
                utility.level = values[3].toInt().coerceIn(1, utility.maxLevel())
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
            pendingPerkWaves.clear()
            preferences.getString("run_pending_perk_waves", "").orEmpty().split(',').filter { it.isNotBlank() }.take(32).forEach { pendingPerkWaves.add(it.toInt().coerceAtLeast(1)) }
            storedTowers.clear()
            storedTraps.clear()
            storedStructures.clear()
            val saveVersion = prefInt("run_save_version", 1)

            fun entries(key: String): List<String> = preferences.getString(key, "").orEmpty()
                .split(';').filter { it.isNotBlank() }.take(MAX_INVENTORY_CAPACITY)

            fun loadLegacyTrapInventory() {
                entries("run_inventory").forEach { entry ->
                    val values = entry.split(',')
                    if (saveVersion >= 3 && values.size >= 4) {
                        storedTraps.add(
                            StoredTrap(
                                TrapKind.valueOf(values[0]),
                                values[1].toInt().coerceIn(1, 3),
                                values[2].toInt().coerceIn(0, 999),
                                if (values[3] != "NONE") Imbuement.valueOf(values[3]) else null,
                                if (values.size >= 5) values[4].toInt().coerceIn(0, 2_000_000_000) else 0
                            )
                        )
                    } else {
                        repeat(values.getOrElse(1) { "0" }.toInt().coerceIn(0, 4)) {
                            storedTraps.add(StoredTrap(TrapKind.valueOf(values[0])))
                        }
                    }
                }
            }

            if (saveVersion >= 4) {
                entries("run_inventory_towers").forEach { entry ->
                    val values = entry.split(',')
                    if (values.size < 5) return@forEach
                    val kind = TowerKind.valueOf(values[0])
                    var evolution = if (values[3] != "NONE") TowerEvolution.valueOf(values[3]) else null
                    if (evolution != null && evolution.kind != kind) evolution = null
                    storedTowers.add(
                        StoredTower(
                            kind,
                            values[1].toInt().coerceIn(1, 3),
                            values[2].toInt().coerceIn(0, 999),
                            evolution,
                            if (values[4] != "NONE") Imbuement.valueOf(values[4]) else null,
                            if (values.size >= 6) values[5].toInt().coerceIn(0, 2_000_000_000) else 0
                        )
                    )
                }
                val trapEntries = entries("run_inventory_traps")
                if (trapEntries.isEmpty()) {
                    // A v4 key may be absent if a previous write was interrupted; preserve the
                    // v3-compatible mirror rather than silently dropping the old trap shelf.
                    loadLegacyTrapInventory()
                } else {
                    trapEntries.forEach { entry ->
                        val values = entry.split(',')
                        if (values.size < 4) return@forEach
                        storedTraps.add(
                            StoredTrap(
                                TrapKind.valueOf(values[0]),
                                values[1].toInt().coerceIn(1, 3),
                                values[2].toInt().coerceIn(0, 999),
                                if (values[3] != "NONE") Imbuement.valueOf(values[3]) else null,
                                if (values.size >= 5) values[4].toInt().coerceIn(0, 2_000_000_000) else 0
                            )
                        )
                    }
                }
                entries("run_inventory_structures").forEach { entry ->
                    val values = entry.split(',')
                    if (values.size < 5) return@forEach
                    val kind = UtilityKind.valueOf(values[0])
                    val maxLevel = if (kind == UtilityKind.BLOCK_GENERATOR) 99 else 3
                    storedStructures.add(
                        StoredStructure(
                            kind,
                            values[1].toInt().coerceIn(1, maxLevel),
                            if (values[2] != "NONE") Imbuement.valueOf(values[2]) else null,
                            values[3].toInt().coerceIn(0, 20),
                            values[4].toInt().coerceIn(0, 2_000_000_000)
                        )
                    )
                }
            } else {
                // v1–v3 only had run_inventory, and every entry was a trap. Migrate it into the
                // dedicated Trap Inventory unchanged; Tower and Structure shelves begin empty.
                loadLegacyTrapInventory()
            }
            while (storedTowers.size > MAX_INVENTORY_CAPACITY) storedTowers.removeAt(storedTowers.lastIndex)
            while (storedTraps.size > MAX_INVENTORY_CAPACITY) storedTraps.removeAt(storedTraps.lastIndex)
            while (storedStructures.size > MAX_INVENTORY_CAPACITY) storedStructures.removeAt(storedStructures.lastIndex)
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
            lastClearedWave = waveNumber
            nextWaveTimer = preferences.getFloat("run_next_wave_timer", if (waveNumber > 0) AUTO_NEXT_WAVE_DELAY else -1f).coerceIn(-1f, AUTO_NEXT_WAVE_DELAY)
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
            activeWaveNumbers.clear()
            pathComplete = true
            selectedTool = if (challengeModifier == ChallengeModifier.TRAPS_ONLY) BuildTool.SPIKES else BuildTool.BOLT
            selectedTower = null
            selectedTrap = null
            selectedUtility = null
            selectedUtilityKind = null
            selectedInventorySelection = null
            selectedCorruption = null
            inventoryCategory = InventoryCategory.TOWERS
            inventoryPageIndex = 0
            buildShelfSlide = 0f
            buildPage = if (challengeModifier == ChallengeModifier.TRAPS_ONLY) BuildPage.TRAPS else BuildPage.TOWERS
            rebuildToolRects()
            phase = if (perkChoices.size == 3) GamePhase.PERK_DRAFT else GamePhase.BUILD
            if (phase != GamePhase.BUILD) nextWaveTimer = -1f
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
        if (path.firstOrNull() != GridCell(0, START_ROW) || path.lastOrNull() != GridCell(COLS - 1, START_ROW)) return false
        if (path.toSet().size != path.size) return false
        return path.all { it.col in 0 until COLS && it.row in 0 until ROWS } && path.zipWithNext().all { abs(it.first.col - it.second.col) + abs(it.first.row - it.second.row) == 1 }
    }

    private fun clearSavedRun() {
        val editor = preferences.edit()
        arrayOf("run_path", "run_towers", "run_traps", "run_utilities", "run_corruptions", "run_perks", "run_pending_perks", "run_pending_perk_waves", "run_next_wave_timer", "run_inventory", "run_inventory_towers", "run_inventory_traps", "run_inventory_structures", "run_supplies", "run_gold", "run_lives", "run_max_core", "run_score", "run_wave", "run_forge_charges", "run_evolution_cores", "run_salvage_parts", "run_growth_essence", "run_survey_lens_waves", "run_phase_barrier", "run_mode", "run_modifier", "run_seed", "run_save_version").forEach { editor.remove(it) }
        editor.putBoolean("has_saved_run", false).apply()
        savedRunAvailable = false
    }

    private fun newRun(mode: GameMode = GameMode.ENDLESS, seed: Long = 7331L, modifier: ChallengeModifier = ChallengeModifier.NONE) {
        titlePressedAction = TitleMenuAction.NONE
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
        selectedInventorySelection = null
        selectedCorruption = null
        evolutionTower = null
        imbuementTower = null
        imbuementTrap = null
        imbuementUtility = null
        inventoryCategory = InventoryCategory.TOWERS
        inventoryPageIndex = 0
        buildShelfSlide = 0f
        buildPage = if (challengeModifier == ChallengeModifier.TRAPS_ONLY) BuildPage.TRAPS else BuildPage.TOWERS
        rebuildToolRects()
        pathCells.clear()
        pathCells.add(GridCell(0, START_ROW))
        reforgeOriginalPath.clear()
        towers.clear()
        traps.clear()
        utilities.clear()
        storedTowers.clear()
        storedTraps.clear()
        storedStructures.clear()
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
        activeWaveNumbers.clear()
        pendingPerkWaves.clear()
        gold = STARTING_BLOCKS
        maxCore = if (challengeModifier == ChallengeModifier.FRAGILE_CORE) 5 else STARTING_CORE
        lives = maxCore
        score = 0
        waveNumber = 0
        lastClearedWave = 0
        nextWaveTimer = -1f
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
        endSeedInput()
        val calendar = Calendar.getInstance()
        val seed = calendar.get(Calendar.YEAR).toLong() * 10000L + (calendar.get(Calendar.MONTH) + 1).toLong() * 100L + calendar.get(Calendar.DAY_OF_MONTH).toLong()
        newRun(GameMode.DAILY, seed, modifierForSeed(seed))
    }

    private fun startCustomChallenge() {
        if (phase != GamePhase.CHALLENGE_MENU) return
        synchronized(stateLock) {
            if (phase != GamePhase.CHALLENGE_MENU) return@synchronized
            endSeedInput()
            val seed = customSeedValue()
            newRun(GameMode.CUSTOM, seed, modifierForSeed(seed))
        }
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
        titlePressedAction = TitleMenuAction.NONE
        phase = GamePhase.TITLE
        enemies.clear()
        pendingSpawns.clear()
        projectiles.clear()
        selectedTower = null
        selectedTrap = null
        selectedUtility = null
        selectedUtilityKind = null
        selectedInventorySelection = null
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
        storedTowers.clear()
        storedTraps.clear()
        storedStructures.clear()
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
        activeWaveNumbers.clear()
        pendingPerkWaves.clear()
        nextWaveTimer = -1f
        selectedTool = if (challengeModifier == ChallengeModifier.TRAPS_ONLY) BuildTool.SPIKES else BuildTool.DIG
        selectedTower = null
        selectedTrap = null
        selectedUtility = null
        selectedUtilityKind = null
        selectedInventorySelection = null
        selectedCorruption = null
        evolutionTower = null
        imbuementTower = null
        imbuementTrap = null
        imbuementUtility = null
        inventoryCategory = InventoryCategory.TOWERS
        inventoryPageIndex = 0
        buildShelfSlide = 0f
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

    private fun persistCustomSeed() {
        preferences.edit().putString("custom_seed_text", customSeedText).apply()
    }

    private fun sanitizeSeedInput(value: String): String = value.filter { it in '0'..'9' }

    private fun commitSeedInput(value: String) {
        synchronized(stateLock) {
            val clean = sanitizeSeedInput(value)
            if (clean.isEmpty()) return
            var prefix = customSeedText
            if (seedComposingText.isNotEmpty() && prefix.endsWith(seedComposingText)) prefix = prefix.dropLast(seedComposingText.length)
            if (seedInputReplaceOnNextInput) {
                prefix = ""
                seedInputReplaceOnNextInput = false
            }
            seedComposingText = ""
            customSeedText = (prefix + clean.take((MAX_SEED_CHARACTERS - prefix.length).coerceAtLeast(0))).take(MAX_SEED_CHARACTERS)
            persistCustomSeed()
            invalidate()
        }
    }

    private fun updateSeedComposition(value: String) {
        synchronized(stateLock) {
            val clean = sanitizeSeedInput(value)
            if (clean.isEmpty() && seedComposingText.isEmpty()) return
            var prefix = customSeedText
            if (seedComposingText.isNotEmpty() && prefix.endsWith(seedComposingText)) prefix = prefix.dropLast(seedComposingText.length)
            if (seedInputReplaceOnNextInput && clean.isNotEmpty()) {
                prefix = ""
                seedInputReplaceOnNextInput = false
            }
            seedComposingText = clean.take((MAX_SEED_CHARACTERS - prefix.length).coerceAtLeast(0))
            customSeedText = (prefix + seedComposingText).take(MAX_SEED_CHARACTERS)
            persistCustomSeed()
            invalidate()
        }
    }

    private fun deleteSeedInput(count: Int) {
        synchronized(stateLock) {
            if (seedInputReplaceOnNextInput) {
                customSeedText = ""
                seedInputReplaceOnNextInput = false
                seedComposingText = ""
            } else if (seedComposingText.isNotEmpty()) {
                customSeedText = customSeedText.dropLast(seedComposingText.length)
                seedComposingText = ""
            } else {
                customSeedText = customSeedText.dropLast(count.coerceAtMost(customSeedText.length))
            }
            persistCustomSeed()
            invalidate()
        }
    }

    private fun beginSeedInput() {
        if (phase != GamePhase.CHALLENGE_MENU) return
        seedInputActive = true
        seedInputReplaceOnNextInput = true
        seedComposingText = ""
        requestFocus()
        post {
            if (!seedInputActive || phase != GamePhase.CHALLENGE_MENU) return@post
            val inputManager = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            inputManager?.restartInput(this)
            inputManager?.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
        }
        invalidate()
    }

    private fun endSeedInput() {
        seedInputActive = false
        seedInputReplaceOnNextInput = false
        seedComposingText = ""
        val inputManager = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        inputManager?.hideSoftInputFromWindow(windowToken, 0)
        clearFocus()
        invalidate()
    }

    private fun customSeedValue(): Long {
        val value = customSeedText.toLongOrNull() ?: 0L
        return if (value == 0L) 1L else value
    }

    private fun nextWaveCountdownSeconds(): Int = max(1, (nextWaveTimer + 0.99f).toInt())

    /** Shortened countdown label, e.g. "1M" at 60s or "45S" below a minute. */
    private fun countdownLabel(): String {
        val s = nextWaveCountdownSeconds()
        return if (s >= 60) "${s / 60}M" else "${s}S"
    }

    private fun burst(x: Float, y: Float, color: Int, count: Int, force: Float) {
        repeat(count) {
            val angle = random.nextFloat() * 6.28318f
            val speed = (0.25f + random.nextFloat() * 0.75f) * force
            val life = 0.28f + random.nextFloat() * 0.48f
            particles.add(Particle(x, y, cos(angle) * speed, sin(angle) * speed, life, life, color, 0.05f + random.nextFloat() * 0.08f, random.nextBoolean()))
        }
    }

    private fun titleActionAt(x: Float, y: Float): TitleMenuAction {
        return when {
            titlePlayRect.contains(x, y) -> TitleMenuAction.PLAY
            titleContinueRect.contains(x, y) && savedRunAvailable -> TitleMenuAction.CONTINUE
            titleChallengeRect.contains(x, y) -> TitleMenuAction.CHALLENGE
            titleSoundRect.contains(x, y) -> TitleMenuAction.SOUND
            else -> TitleMenuAction.NONE
        }
    }

    private fun activateTitleAction(action: TitleMenuAction) {
        when (action) {
            TitleMenuAction.PLAY -> newRun()
            TitleMenuAction.CONTINUE -> if (savedRunAvailable) loadSavedRun()
            TitleMenuAction.CHALLENGE -> {
                phase = GamePhase.CHALLENGE_MENU
                audio.play("ui_click", 0.38f, 1.13f)
            }
            TitleMenuAction.SOUND -> audio.toggle()
            TitleMenuAction.NONE -> Unit
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        synchronized(stateLock) {
            val x = event.x
            val y = event.y
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_POINTER_DOWN -> {
                    uiTouchActive = true
                    uiTouchX = x
                    uiTouchY = y
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> uiTouchActive = false
            }

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
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> titlePressedAction = titleActionAt(x, y)
                    MotionEvent.ACTION_CANCEL -> titlePressedAction = TitleMenuAction.NONE
                    MotionEvent.ACTION_UP -> {
                        val heldAction = titlePressedAction
                        titlePressedAction = TitleMenuAction.NONE
                        // Trigger only when the finger is released over the same active sprite.
                        // This makes menu controls feel deliberate and avoids accidental releases.
                        if (heldAction != TitleMenuAction.NONE && heldAction == titleActionAt(x, y)) {
                            activateTitleAction(heldAction)
                        }
                    }
                }
                return true
            }

            if (phase == GamePhase.CHALLENGE_MENU) {
                if (event.action == MotionEvent.ACTION_UP) {
                    when {
                        challengeDailyRect.contains(x, y) -> startDailyChallenge()
                        challengeSeedStartRect.contains(x, y) -> startCustomChallenge()
                        challengeBackRect.contains(x, y) -> {
                            endSeedInput()
                            phase = GamePhase.TITLE
                        }
                        seedInputRect.contains(x, y) -> beginSeedInput()
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
                if (feedbackToggleRect.contains(x, y)) {
                    feedbackEnabled = !feedbackEnabled
                    preferences.edit().putBoolean("feedback_enabled", feedbackEnabled).apply()
                    if (feedbackEnabled) setBanner("FEEDBACK TEXT ON", 1.4f) else bannerTimer = 0f
                    return true
                }
                if (resetPathRect.contains(x, y)) {
                    if (phase == GamePhase.REFORGE) cancelReforge()
                    else if (phase == GamePhase.BUILD && waveNumber == 0) resetPath()
                    else if (phase == GamePhase.BUILD) startReforge()
                    return true
                }
                // v1.4.4 Forge Overdrive activation
                if (overdriveRect.contains(x, y)) {
                    if (overdriveCharge >= OVERDRIVE_MAX && !overdriveActive) {
                        activateOverdrive()
                    } else if (overdriveActive) {
                        setBanner("OVERDRIVE ALREADY ACTIVE", 1.2f)
                    } else {
                        setBanner("OVERDRIVE ${overdriveCharge.toInt()}/${OVERDRIVE_MAX.toInt()}", 1.2f)
                    }
                    return true
                }
                if (primaryActionRect.contains(x, y)) {
                    when (phase) {
                        GamePhase.BUILD -> startWave()
                        GamePhase.REFORGE -> confirmReforge()
                        GamePhase.WAVE -> stackNextWave()
                        else -> Unit
                    }
                    return true
                }
            }

            if ((selectedTower != null || selectedTrap != null || selectedUtility != null || selectedCorruption != null) && (phase == GamePhase.BUILD || phase == GamePhase.WAVE) && event.action == MotionEvent.ACTION_UP) {
                if (backRect.contains(x, y)) {
                    selectedTower = null
                    selectedTrap = null
                    selectedUtility = null
                    selectedCorruption = null
                    audio.play("ui_click", 0.3f, 1f)
                    return true
                }
                if (upgradeRect.contains(x, y)) {
                    val tower = selectedTower
                    when {
                        selectedCorruption != null -> cleanseSelectedCorruption()
                        selectedUtility != null -> upgradeSelectedUtility()
                        tower?.canEvolve() == true -> if (evolutionCores > 0) openEvolutionDraft(tower) else setBanner("DEFEAT AN OVERGROWTH BOSS FOR AN EVOLUTION CORE", 2f)
                        else -> upgradeSelectedDefense()
                    }
                    return true
                }
                val utility = selectedUtility
                if (utility?.kind == UtilityKind.FORGE_WORKSHOP && workshopRect.contains(x, y)) {
                    openWorkshop()
                    return true
                }
                if (utility?.kind == UtilityKind.FORGE_WORKSHOP && structureStoreRect.contains(x, y)) {
                    storeSelectedStructure()
                    return true
                }
                if (utility?.kind != UtilityKind.FORGE_WORKSHOP && storeRect.contains(x, y)) {
                    when {
                        selectedTower != null || selectedTrap != null -> storeSelectedDefense()
                        utility != null -> storeSelectedStructure()
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

            if (phase == GamePhase.BUILD && buildShelfReadyForInput() && event.action == MotionEvent.ACTION_UP) {
                if (buildPage == BuildPage.INVENTORY) {
                    fun selectInventoryCategory(category: InventoryCategory) {
                        if (inventoryCategory == category) {
                            when (category) {
                                InventoryCategory.TOWERS -> {
                                    if (challengeModifier == ChallengeModifier.TRAPS_ONLY) {
                                        setBanner("TRAPS ONLY CHALLENGE", 1.4f)
                                        return
                                    }
                                    buildPage = BuildPage.TOWERS
                                    if (selectedTool.ordinal >= BuildTool.SPIKES.ordinal) selectedTool = BuildTool.BOLT
                                }
                                InventoryCategory.TRAPS -> {
                                    if (challengeModifier == ChallengeModifier.TOWERS_ONLY) {
                                        setBanner("TOWERS ONLY CHALLENGE", 1.4f)
                                        return
                                    }
                                    buildPage = BuildPage.TRAPS
                                    if (selectedTool.ordinal < BuildTool.SPIKES.ordinal) selectedTool = BuildTool.SPIKES
                                }
                                InventoryCategory.STRUCTURES -> buildPage = BuildPage.STRUCTURES
                            }
                        } else {
                            inventoryCategory = category
                            inventoryPageIndex = 0
                        }
                        clearBuildSelections()
                        rebuildToolRects()
                        audio.play("ui_click", 0.28f, 0.98f)
                    }
                    if (towerPageRect.contains(x, y)) { selectInventoryCategory(InventoryCategory.TOWERS); return true }
                    if (trapPageRect.contains(x, y)) { selectInventoryCategory(InventoryCategory.TRAPS); return true }
                    if (utilityPageRect.contains(x, y)) { selectInventoryCategory(InventoryCategory.STRUCTURES); return true }
                    if (inventoryPageRect.contains(x, y)) {
                        inventoryPageIndex = (inventoryPageIndex + 1) % inventoryPageCount()
                        selectedInventorySelection = null
                        rebuildToolRects()
                        audio.play("ui_click", 0.28f, 0.92f)
                        return true
                    }
                } else {
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
                        if (buildPage == BuildPage.STRUCTURES) utilityPageIndex = (utilityPageIndex + 1) % 5 else buildPage = BuildPage.STRUCTURES
                        clearBuildSelections()
                        rebuildToolRects()
                        audio.play("ui_click", 0.28f, 1.08f)
                        return true
                    }
                    if (inventoryPageRect.contains(x, y)) {
                        buildPage = BuildPage.INVENTORY
                        inventoryPageIndex = 0
                        clearBuildSelections()
                        rebuildToolRects()
                        audio.play("ui_click", 0.28f, 0.92f)
                        return true
                    }
                }
                for (entry in toolRects) if (entry.second.contains(x, y)) { selectTool(entry.first); return true }
                for (entry in utilityRects) if (entry.second.contains(x, y)) {
                    clearBuildSelections()
                    selectedUtilityKind = entry.first
                    audio.play("ui_click", 0.28f, 1.04f)
                    val kind = entry.first
                    val status = if (kind == UtilityKind.BLOCK_GENERATOR) "  ${utilities.count { it.kind == kind }}/$MAX_BLOCK_GENERATORS" else ""
                    setBanner("${kind.title.uppercase()}$status", 0.9f)
                    return true
                }
                for (entry in inventoryRects) if (entry.second.contains(x, y)) {
                    clearBuildSelections()
                    selectedInventorySelection = entry.first
                    audio.play("ui_click", 0.28f, 0.98f)
                    setBanner("FREE TO PLACE", 0.9f)
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
        selectedInventorySelection = null
    }

    private fun selectTool(tool: BuildTool) {
        if (phase != GamePhase.BUILD) return
        if (challengeModifier == ChallengeModifier.TRAPS_ONLY && tool.ordinal < BuildTool.SPIKES.ordinal) return
        if (challengeModifier == ChallengeModifier.TOWERS_ONLY && tool.ordinal >= BuildTool.SPIKES.ordinal) return
        clearBuildSelections()
        selectedTool = tool
        audio.play("ui_click", 0.28f, 1f + tool.ordinal * 0.025f)
        setBanner(tool.title.uppercase(), 0.8f)
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
        if (existingCorruption != null && (phase == GamePhase.BUILD || phase == GamePhase.WAVE)) {
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
            setBanner(existingTower.kind.title.uppercase(), 0.7f)
            return
        }
        val existingUtility = findUtility(cell.col, cell.row)
        if (existingUtility != null) {
            selectedUtility = existingUtility
            selectedTower = null
            selectedTrap = null
            selectedCorruption = null
            audio.play("ui_click", 0.28f, 0.92f)
            val status = if (existingUtility.kind == UtilityKind.BLOCK_GENERATOR) "  ${utilities.count { it.kind == existingUtility.kind }}/$MAX_BLOCK_GENERATORS" else ""
            setBanner("${existingUtility.kind.title.uppercase()}$status", 0.7f)
            return
        }
        val existingTrap = findTrap(cell.col, cell.row)
        if (existingTrap != null) {
            selectedTrap = existingTrap
            selectedTower = null
            selectedUtility = null
            selectedCorruption = null
            audio.play("ui_click", 0.28f, 1.06f)
            setBanner(existingTrap.kind.title.uppercase(), 0.7f)
            return
        }
        if (phase != GamePhase.BUILD) return
        selectedTower = null
        selectedTrap = null
        selectedUtility = null
        selectedCorruption = null
        if (buildPage == BuildPage.STRUCTURES) {
            val kind = selectedUtilityKind ?: return
            placeUtility(cell, kind)
            return
        }
        if (buildPage == BuildPage.INVENTORY) {
            val inventorySelection = selectedInventorySelection ?: return
            if (isValidInventorySelection(inventorySelection)) placeStoredInventoryItem(cell, inventorySelection)
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

    private fun towerPlacementCost(kind: TowerKind): Int {
        val count = towers.count { it.kind == kind }
        return kind.cost + count * max(10, kind.cost / 2)
    }

    private fun trapPlacementCost(kind: TrapKind): Int {
        val count = traps.count { it.kind == kind }
        return kind.cost + count * max(10, kind.cost / 2)
    }

    private fun utilityPlacementCost(kind: UtilityKind): Int {
        val count = utilities.count { it.kind == kind }
        return kind.cost + count * max(10, kind.cost / 2)
    }

    private fun towerKindForTool(tool: BuildTool): TowerKind = when (tool) {
        BuildTool.BOLT -> TowerKind.BOLT
        BuildTool.FROST -> TowerKind.FROST
        BuildTool.CANNON -> TowerKind.CANNON
        BuildTool.EMBER -> TowerKind.EMBER
        BuildTool.BEACON -> TowerKind.BEACON
        BuildTool.THORN -> TowerKind.THORN
        BuildTool.LANCE -> TowerKind.LANCE
        BuildTool.MIRE -> TowerKind.MIRE
        BuildTool.GALE -> TowerKind.GALE
        BuildTool.SUNFORGE -> TowerKind.SUNFORGE
        BuildTool.LODESTONE -> TowerKind.LODESTONE
        BuildTool.HOWL -> TowerKind.HOWL
        BuildTool.VITRIOL -> TowerKind.VITRIOL
        BuildTool.GRAVEBOLT -> TowerKind.GRAVEBOLT
        BuildTool.AEGIS_LOOM -> TowerKind.AEGIS_LOOM
        else -> TowerKind.BOLT
    }

    /** Current placement cost for a build-card tool, escalating with how many of its kind are already placed. */
    private fun toolPlacementCost(tool: BuildTool): Int = when (tool) {
        BuildTool.SPIKES -> trapPlacementCost(TrapKind.SPIKE)
        BuildTool.ROOT -> trapPlacementCost(TrapKind.ROOT)
        BuildTool.RUNE -> trapPlacementCost(TrapKind.EMBER)
        BuildTool.ARC -> trapPlacementCost(TrapKind.ARC)
        BuildTool.CRUSHER -> trapPlacementCost(TrapKind.CRUSHER)
        BuildTool.DIG -> 0
        else -> towerPlacementCost(towerKindForTool(tool))
    }

    private fun placeTower(cell: GridCell, kind: TowerKind) {
        if (challengeModifier == ChallengeModifier.TRAPS_ONLY) return
        if (isPathCell(cell) || findTower(cell.col, cell.row) != null || findTrap(cell.col, cell.row) != null || findUtility(cell.col, cell.row) != null || findCorruption(cell.col, cell.row)?.kind == CorruptionKind.BROOD_NEST) {
            setBanner("TOWERS NEED A FREE TERRAIN BLOCK", 1.5f)
            audio.play("ui_click", 0.24f, 0.7f)
            return
        }
        var cost = towerPlacementCost(kind)
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
        val gateCell = pathCells.firstOrNull()
        val coreCell = pathCells.lastOrNull()
        if (!isPathCell(cell) || cell == gateCell || cell == coreCell || findTrap(cell.col, cell.row) != null) {
            setBanner("TRAPS GO ON AN EMPTY PATH BLOCK", 1.5f)
            audio.play("ui_click", 0.24f, 0.7f)
            return
        }
        val cost = trapPlacementCost(kind)
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

    /** Stores either defense family without selling it; permanent rank state is retained. */
    private fun storeSelectedDefense() {
        val tower = selectedTower
        val trap = selectedTrap
        val category = if (tower != null) InventoryCategory.TOWERS else InventoryCategory.TRAPS
        if (tower == null && trap == null) return
        if (!hasInventoryRoom(category)) {
            setBanner("${category.title} INVENTORY FULL  ${inventoryCount(category)}/${inventoryCapacity()}", 1.7f)
            return
        }
        val wrapped = supplyCount(CraftedItem.RECOVERY_WRAP) > 0
        val cost = if (wrapped) 0 else tower?.let { towerStorageCost(it) } ?: trap?.let { trapStorageCost(it) } ?: 0
        if (gold < cost) {
            setBanner("NEED $cost BLOCKS TO STORE", 1.6f)
            return
        }
        gold -= cost
        if (wrapped) consumeSupply(CraftedItem.RECOVERY_WRAP)
        val title: String
        if (tower != null) {
            storedTowers.add(StoredTower(tower.kind, tower.level, tower.overcharge, tower.evolution, tower.imbuement, tower.activationCount))
            towers.remove(tower)
            title = tower.kind.title
        } else {
            val storedTrap = trap ?: return
            storedTraps.add(StoredTrap(storedTrap.kind, storedTrap.level, storedTrap.overcharge, storedTrap.imbuement, storedTrap.activationCount))
            traps.remove(storedTrap)
            title = storedTrap.kind.title
        }
        selectedTower = null
        selectedTrap = null
        openInventoryAfterStore(category)
        setBanner("${title.uppercase()} STORED${if (wrapped) "  •  WRAP USED" else "  •  -$cost BLOCKS"}", 1.8f)
        saveRun()
    }

    /** Stores a Structure, including wave-cycle progress and activation cadence. */
    private fun storeSelectedStructure() {
        val structure = selectedUtility ?: return
        if (!hasInventoryRoom(InventoryCategory.STRUCTURES)) {
            setBanner("STRUCTURES INVENTORY FULL  ${storedStructures.size}/${inventoryCapacity()}", 1.7f)
            return
        }
        // An online Inventory Depot can raise every shelf above 25. Do not let its own removal
        // strand any shelf over the base limit it is about to restore.
        if (lastDepotWouldOverfillInventory(structure, addingToStructureShelf = true)) {
            setBanner("KEEP INVENTORY DEPOT ONLINE UNTIL EVERY SHELF IS 25 OR LOWER", 2f)
            return
        }
        val wrapped = supplyCount(CraftedItem.RECOVERY_WRAP) > 0
        val cost = if (wrapped) 0 else structureStorageCost(structure)
        if (gold < cost) {
            setBanner("NEED $cost BLOCKS TO STORE", 1.6f)
            return
        }
        gold -= cost
        if (wrapped) consumeSupply(CraftedItem.RECOVERY_WRAP)
        storedStructures.add(
            StoredStructure(
                structure.kind,
                structure.level,
                structure.imbuement,
                structure.productionProgress,
                structure.activationCount
            )
        )
        utilities.remove(structure)
        selectedUtility = null
        openInventoryAfterStore(InventoryCategory.STRUCTURES)
        setBanner("${structure.kind.title.uppercase()} STORED${if (wrapped) "  •  WRAP USED" else "  •  -$cost BLOCKS"}", 1.8f)
        saveRun()
    }

    private fun openInventoryAfterStore(category: InventoryCategory) {
        buildPage = BuildPage.INVENTORY
        inventoryCategory = category
        inventoryPageIndex = max(0, (inventoryCount(category) - 1) / INVENTORY_PAGE_SIZE)
        selectedInventorySelection = InventorySelection(category, inventoryCount(category) - 1)
        rebuildToolRects()
    }

    private fun placeStoredInventoryItem(cell: GridCell, selection: InventorySelection) {
        when (selection.category) {
            InventoryCategory.TOWERS -> placeStoredTower(cell, selection)
            InventoryCategory.TRAPS -> placeStoredTrap(cell, selection)
            InventoryCategory.STRUCTURES -> placeStoredStructure(cell, selection)
        }
    }

    /** Free re-placement of a tower deliberately skips escalating build costs. */
    private fun placeStoredTower(cell: GridCell, selection: InventorySelection) {
        if (challengeModifier == ChallengeModifier.TRAPS_ONLY || selection.index !in storedTowers.indices) return
        if (isPathCell(cell) || findTower(cell.col, cell.row) != null || findTrap(cell.col, cell.row) != null || findUtility(cell.col, cell.row) != null || findCorruption(cell.col, cell.row)?.kind == CorruptionKind.BROOD_NEST) {
            setBanner("STORED TOWERS NEED A FREE TERRAIN BLOCK", 1.6f)
            return
        }
        val stored = storedTowers.removeAt(selection.index)
        val tower = Tower(cell.col, cell.row, stored.kind)
        tower.level = stored.level
        tower.overcharge = stored.overcharge
        tower.evolution = stored.evolution?.takeIf { it.kind == stored.kind }
        tower.imbuement = stored.imbuement
        tower.activationCount = stored.activationCount
        towers.add(tower)
        finishInventoryDeployment(InventoryCategory.TOWERS)
        burst(cell.col + 0.5f, cell.row + 0.5f, stored.kind.accent, 13, 0.8f)
        setBanner("${stored.kind.title.uppercase()} REDEPLOYED FREE  •  ${stored.rankLabel()}", 1.7f)
        saveRun()
    }

    /** Free re-placement of a trap keeps its upgrades and imbuement intact. */
    private fun placeStoredTrap(cell: GridCell, selection: InventorySelection) {
        if (challengeModifier == ChallengeModifier.TOWERS_ONLY || selection.index !in storedTraps.indices) return
        val gateCell = pathCells.firstOrNull()
        val coreCell = pathCells.lastOrNull()
        if (!isPathCell(cell) || cell == gateCell || cell == coreCell || findTrap(cell.col, cell.row) != null) {
            setBanner("STORED TRAPS NEED AN EMPTY PATH BLOCK", 1.6f)
            return
        }
        val stored = storedTraps.removeAt(selection.index)
        val trap = SpikeTrap(nextTrapId++, cell.col, cell.row, stored.kind)
        trap.level = stored.level
        trap.overcharge = stored.overcharge
        trap.imbuement = stored.imbuement
        trap.activationCount = stored.activationCount
        traps.add(trap)
        finishInventoryDeployment(InventoryCategory.TRAPS)
        burst(cell.col + 0.5f, cell.row + 0.5f, stored.kind.accent, 13, 0.8f)
        setBanner("${stored.kind.title.uppercase()} REDEPLOYED FREE  •  ${stored.rankLabel()}", 1.7f)
        saveRun()
    }

    /** Free re-placement of a Structure bypasses unlock/cost gates but preserves normal board limits. */
    private fun placeStoredStructure(cell: GridCell, selection: InventorySelection) {
        if (selection.index !in storedStructures.indices) return
        val stored = storedStructures[selection.index]
        if (!structurePlacementAllowed(cell, stored.kind)) return
        storedStructures.removeAt(selection.index)
        val structure = Utility(cell.col, cell.row, stored.kind)
        structure.level = stored.level.coerceIn(1, structure.maxLevel())
        structure.imbuement = stored.imbuement
        structure.productionProgress = stored.productionProgress.coerceIn(0, 20)
        structure.activationCount = stored.activationCount.coerceIn(0, 2_000_000_000)
        utilities.add(structure)
        selectedUtility = structure
        selectedUtilityKind = null
        finishInventoryDeployment(InventoryCategory.STRUCTURES)
        burst(cell.col + 0.5f, cell.row + 0.5f, stored.kind.accent, 16, 0.95f)
        setBanner("${stored.kind.title.uppercase()} REDEPLOYED FREE  •  ${stored.rankLabel()}", 1.8f)
        saveRun()
    }

    private fun finishInventoryDeployment(category: InventoryCategory) {
        selectedInventorySelection = null
        inventoryPageIndex = min(inventoryPageIndex, inventoryPageCount(category) - 1)
        rebuildToolRects()
    }

    private fun utilityUnlocked(kind: UtilityKind): Boolean {
        return waveNumber >= 10 || kind == UtilityKind.BLOCK_GENERATOR || kind == UtilityKind.SURVEYOR_STATION || kind == UtilityKind.SALVAGE_YARD
    }

    private fun structurePlacementAllowed(cell: GridCell, kind: UtilityKind): Boolean {
        // Structures have no global cap: unique kinds remain unique while Block Generators can
        // occupy up to five terrain cells. Stored redeployments obey these board rules too.
        val copies = utilities.count { it.kind == kind }
        if (kind == UtilityKind.BLOCK_GENERATOR && copies >= MAX_BLOCK_GENERATORS) {
            setBanner("BLOCK GENERATOR LIMIT  $MAX_BLOCK_GENERATORS/$MAX_BLOCK_GENERATORS", 1.6f)
            return false
        }
        if (kind != UtilityKind.BLOCK_GENERATOR && copies >= 1) {
            setBanner("ONE COPY OF THIS STRUCTURE IS ALREADY ACTIVE", 1.5f)
            return false
        }
        if (isPathCell(cell) || findTower(cell.col, cell.row) != null || findUtility(cell.col, cell.row) != null || findCorruption(cell.col, cell.row) != null) {
            setBanner("STRUCTURES NEED CLEAN FREE TERRAIN", 1.5f)
            return false
        }
        return true
    }

    private fun placeUtility(cell: GridCell, kind: UtilityKind) {
        if (!utilityUnlocked(kind)) {
            setBanner("DEFEAT THE FIRST OVERGROWTH TO UNLOCK", 1.8f)
            return
        }
        if (!structurePlacementAllowed(cell, kind)) return
        val cost = utilityPlacementCost(kind)
        if (gold < cost) {
            setBanner("NEED $cost BLOCKS", 1.5f)
            return
        }
        gold -= cost
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
        if (utility.level >= utility.maxLevel()) {
            setBanner("STRUCTURE AT MAXIMUM LEVEL", 1.4f)
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
        if (lastDepotWouldOverfillInventory(utility, addingToStructureShelf = false)) {
            setBanner("KEEP INVENTORY DEPOT ONLINE UNTIL EVERY SHELF IS 25 OR LOWER", 2f)
            return
        }
        val invested = utility.kind.cost + (utility.level - 1) * (utility.kind.cost / 2 + 70)
        val value = (invested * recyclingMultiplier()).toInt()
        gold = safeAdd(gold, value)
        val parts = min(20, utility.level + (utilities.filter { it.kind == UtilityKind.SALVAGE_YARD }.map { utilityPowerLevel(it) }.maxOrNull() ?: 0))
        salvageParts = safeAdd(salvageParts, parts)
        utilities.remove(utility)
        selectedUtility = null
        rebuildToolRects()
        setBanner("STRUCTURE RECYCLED  +$value BLOCKS  +$parts PARTS", 1.8f)
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
                if (index == null) { setBanner("NO STORED TRAP CAN BE REFIT", 1.5f); return }
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
        if (pathComplete) return
        val last = pathCells.lastOrNull() ?: return
        val distance = abs(cell.col - last.col) + abs(cell.row - last.row)
        if (distance > 1) {
            var safety = 0
            while (!pathComplete && pathCells.lastOrNull() != cell && safety < COLS + ROWS) {
                val current = pathCells.lastOrNull() ?: break
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
        if (storedTraps.size + displaced.size > inventoryCapacity()) {
            setBanner("TRAP INVENTORY FULL  NEED ${storedTraps.size + displaced.size}/${inventoryCapacity()} SLOTS", 1.9f)
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
        for (trap in displaced) storedTraps.add(StoredTrap(trap.kind, trap.level, trap.overcharge, trap.imbuement, trap.activationCount))
        traps.removeAll(displaced)
        reforgeOriginalPath.clear()
        phase = GamePhase.BUILD
        rebuildToolRects()
        setBanner("REFORGED  -F$forgeCost  -$storageCost BLOCKS  ${displaced.size} STORED", 2.5f)
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
        // v1.4.4 Forge Overdrive — screen-wide gold flash on activation
        if (overdriveFlash > 0.02f) {
            paint.color = Color.argb((overdriveFlash * 120f).toInt().coerceIn(0, 255), 255, 215, 80)
            canvas.drawRect(0f, 0f, viewWidth, viewHeight, paint)
        }
        when (phase) {
            GamePhase.PAUSED -> drawPauseOverlay(canvas)
            GamePhase.VICTORY, GamePhase.GAME_OVER -> drawEndOverlay(canvas)
            GamePhase.PERK_DRAFT -> drawPerkDraft(canvas)
            GamePhase.EVOLUTION_DRAFT -> drawEvolutionDraft(canvas)
            else -> Unit
        }
    }

    private fun drawTitle(canvas: Canvas) {
        // The title is deliberately sparse: environment art sets the tone while the three
        // primary actions remain the only text a new player has to parse.
        drawCoverBitmap(canvas, sprites.titleBackground, RectF(0f, 0f, viewWidth, viewHeight))
        paint.color = Color.argb(44, 2, 8, 5)
        canvas.drawRect(0f, 0f, viewWidth, viewHeight, paint)

        // All title chrome below is pre-rendered sprite art. Canvas is only responsible for
        // adaptive placement, labels, and hit feedback—not drawing flat placeholder buttons.
        drawBitmapInRect(canvas, sprites.menuPanel, titlePanelRect)
        val crestWidth = min(dp(360f), viewWidth * 0.38f)
        val crestHeight = crestWidth * sprites.menuTitleCrest.height / sprites.menuTitleCrest.width
        val crestY = max(crestHeight * 0.58f + dp(8f), titlePanelRect.top - crestHeight * 0.18f)
        drawBitmapCentered(canvas, sprites.menuTitleCrest, viewWidth * 0.5f, crestY, crestWidth)
        drawCenteredText(
            canvas,
            "BLOCKHOLD",
            viewWidth * 0.5f,
            crestY - crestHeight * 0.10f,
            min(dp(34f), crestWidth * 0.088f),
            Color.rgb(245, 249, 228),
            true,
            true
        )
        drawCenteredText(
            canvas,
            "DEFENSE",
            viewWidth * 0.5f,
            crestY + crestHeight * 0.23f,
            min(dp(12f), crestWidth * 0.033f),
            Color.rgb(190, 244, 78),
            true
        )

        drawTitleButton(
            canvas, titlePlayRect, "NEW RUN", sprites.menuIconPlay,
            sprites.menuButtonPrimary, sprites.menuButtonPrimaryPressed,
            TitleMenuAction.PLAY, true, Color.rgb(240, 255, 214)
        )
        drawTitleButton(
            canvas, titleContinueRect, "CONTINUE", sprites.menuIconContinue,
            sprites.menuButtonSecondary, sprites.menuButtonSecondaryPressed,
            TitleMenuAction.CONTINUE, savedRunAvailable,
            if (savedRunAvailable) Color.rgb(221, 250, 255) else Color.rgb(126, 141, 132)
        )
        drawTitleButton(
            canvas, titleChallengeRect, "CHALLENGES", sprites.menuIconChallenge,
            sprites.menuButtonChallenge, sprites.menuButtonChallengePressed,
            TitleMenuAction.CHALLENGE, true, Color.rgb(244, 225, 255)
        )

        val soundSprite = if (audio.isEnabled()) sprites.menuIconSoundOn else sprites.menuIconSoundOff
        drawBitmapInRect(
            canvas,
            soundSprite,
            titleSoundRect,
            if (titlePressedAction == TitleMenuAction.SOUND) 205 else 255
        )
        if (versionLabel.isNotEmpty()) {
            drawText(
                canvas, versionLabel, viewWidth - dp(13f), viewHeight - dp(10f), dp(8f),
                Color.argb(150, 202, 214, 202), Paint.Align.RIGHT, true
            )
        }
    }

    private fun drawTitleButton(
        canvas: Canvas,
        rect: RectF,
        label: String,
        icon: Bitmap,
        normalSkin: Bitmap,
        pressedSkin: Bitmap,
        action: TitleMenuAction,
        enabled: Boolean,
        textColor: Int
    ) {
        val held = enabled && titlePressedAction == action
        val skin = when {
            !enabled -> sprites.menuButtonDisabled
            held -> pressedSkin
            else -> normalSkin
        }
        drawBitmapInRect(canvas, skin, rect, if (enabled) 255 else 190)
        val iconSize = min(rect.height() * 0.68f, dp(38f))
        val iconX = rect.left + rect.width() * 0.215f
        val verticalNudge = if (held) rect.height() * 0.04f else 0f
        spritePaint.alpha = if (enabled) 255 else 110
        drawBitmapCentered(canvas, icon, iconX, rect.centerY() + verticalNudge, iconSize)
        spritePaint.alpha = 255
        drawCenteredText(
            canvas,
            label,
            rect.left + rect.width() * 0.635f,
            rect.centerY() + verticalNudge,
            min(dp(16f), rect.height() * 0.255f),
            textColor,
            true,
            true
        )
    }

    private fun drawChallengeMenu(canvas: Canvas) {
        drawCoverBitmap(canvas, sprites.titleBackground, RectF(0f, 0f, viewWidth, viewHeight))
        paint.color = Color.argb(164, 4, 11, 8)
        canvas.drawRect(0f, 0f, viewWidth, viewHeight, paint)
        drawUiModal(canvas, RectF(viewWidth * 0.08f, viewHeight * 0.07f, viewWidth * 0.92f, viewHeight * 0.91f), 244)
        drawBitmapCentered(canvas, sprites.uiIconSeed, viewWidth * 0.5f, viewHeight * 0.105f, min(dp(34f), viewHeight * 0.065f))
        drawCenteredText(canvas, "CHALLENGES", viewWidth * 0.5f, viewHeight * 0.16f, min(dp(30f), viewHeight * 0.067f), Color.WHITE, true, true)
        drawCenteredText(canvas, "DAILY OR CUSTOM SEED", viewWidth * 0.5f, viewHeight * 0.21f, dp(10f), Color.rgb(188, 204, 191), true)
        val calendar = Calendar.getInstance()
        val dailySeed = calendar.get(Calendar.YEAR).toLong() * 10000L + (calendar.get(Calendar.MONTH) + 1).toLong() * 100L + calendar.get(Calendar.DAY_OF_MONTH).toLong()
        drawChallengeCard(canvas, challengeDailyRect, "DAILY PATH", "SEED $dailySeed", modifierForSeed(dailySeed), Color.rgb(190, 244, 78))
        val customSeed = customSeedValue()
        drawChallengeCard(canvas, challengeSeedStartRect, "CUSTOM SEED", "SEED $customSeed", modifierForSeed(customSeed), Color.rgb(93, 220, 255))
        drawUiPanel(canvas, seedInputRect, active = seedInputActive)
        drawBitmapCentered(canvas, sprites.uiIconSeed, seedInputRect.left + seedInputRect.width() * 0.12f, seedInputRect.centerY(), min(seedInputRect.height() * 0.54f, dp(25f)))
        drawCenteredText(canvas, if (customSeedText.isEmpty()) "ENTER SEED" else customSeedText, seedInputRect.left + seedInputRect.width() * 0.58f, seedInputRect.centerY(), dp(18f), Color.WHITE, true)
        drawCenteredText(canvas, "UP TO $MAX_SEED_CHARACTERS DIGITS  •  SAME SEED, SAME RUN", viewWidth * 0.5f, seedInputRect.bottom + dp(18f), dp(8.5f), Color.rgb(176, 193, 180), true)
        drawCenteredText(canvas, "DAILY  W$bestDailyWave  •  CUSTOM  W$bestCustomWave", viewWidth * 0.5f, viewHeight * 0.80f, dp(10f), Color.rgb(190, 244, 78), true)
        drawUiButton(canvas, challengeBackRect, "BACK", sprites.uiIconBack, UiControlTone.SECONDARY, textSize = min(dp(10f), challengeBackRect.height() * 0.29f))
    }

    private fun drawChallengeCard(canvas: Canvas, rect: RectF, title: String, subtitle: String, modifier: ChallengeModifier, accent: Int) {
        drawUiCard(canvas, rect)
        strokePaint.strokeWidth = dp(1.5f)
        strokePaint.color = Color.argb(172, Color.red(accent), Color.green(accent), Color.blue(accent))
        canvas.drawRoundRect(rect, dp(15f), dp(15f), strokePaint)
        drawBitmapCentered(canvas, sprites.uiIconSeed, rect.left + rect.width() * 0.13f, rect.top + rect.height() * 0.18f, min(rect.height() * 0.20f, dp(22f)))
        drawCenteredText(canvas, title, rect.centerX(), rect.top + rect.height() * 0.22f, dp(17f), accent, true, true)
        drawCenteredText(canvas, subtitle, rect.centerX(), rect.top + rect.height() * 0.41f, min(dp(9f), rect.width() * 0.035f), Color.rgb(194, 207, 197), true)
        drawCenteredText(canvas, modifier.title.uppercase(), rect.centerX(), rect.top + rect.height() * 0.62f, dp(12f), Color.WHITE, true)
        drawWrappedText(canvas, modifier.description, rect.centerX(), rect.top + rect.height() * 0.76f, rect.width() * 0.84f, dp(9f), Color.rgb(174, 191, 178), 2)
    }

    private fun drawPerkDraft(canvas: Canvas) {
        paint.color = Color.argb(232, 6, 12, 9)
        canvas.drawRect(0f, 0f, viewWidth, viewHeight, paint)
        drawUiModal(canvas, RectF(viewWidth * 0.07f, viewHeight * 0.10f, viewWidth * 0.93f, viewHeight * 0.77f), 246)
        drawBitmapCentered(canvas, sprites.uiIconUpgrade, viewWidth * 0.5f, viewHeight * 0.14f, min(dp(38f), viewHeight * 0.07f))
        drawCenteredText(canvas, "FORGE PICK", viewWidth * 0.5f, viewHeight * 0.20f, min(dp(34f), viewHeight * 0.075f), Color.rgb(190, 244, 78), true, true)
        drawCenteredText(canvas, "CHOOSE ONE  •  STACKS PERSIST", viewWidth * 0.5f, viewHeight * 0.26f, dp(10f), Color.rgb(194, 210, 197), true)
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
            drawUiCard(canvas, rect)
            strokePaint.strokeWidth = dp(1.5f)
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
        drawUiModal(canvas, RectF(viewWidth * 0.10f, viewHeight * 0.10f, viewWidth * 0.90f, viewHeight * 0.77f), 246)
        drawBitmapCentered(canvas, sprites.uiIconEvolve, viewWidth * 0.5f, viewHeight * 0.14f, min(dp(38f), viewHeight * 0.07f))
        drawCenteredText(canvas, "EVOLVE", viewWidth * 0.5f, viewHeight * 0.20f, min(dp(34f), viewHeight * 0.075f), Color.rgb(255, 203, 81), true, true)
        drawCenteredText(canvas, "${tower.kind.title.uppercase()}  •  CHOOSE A FORM", viewWidth * 0.5f, viewHeight * 0.26f, dp(10f), Color.rgb(201, 214, 204), true)
        val options = TowerEvolution.choices(tower.kind)
        options.forEachIndexed { index, evolution ->
            if (index >= evolutionRects.size) return@forEachIndexed
            val rect = evolutionRects[index]
            drawUiCard(canvas, rect, active = true)
            strokePaint.strokeWidth = dp(1.5f)
            strokePaint.color = tower.kind.accent
            canvas.drawRoundRect(rect, dp(15f), dp(15f), strokePaint)
            drawWrappedText(canvas, evolution.title.uppercase(), rect.centerX(), rect.top + rect.height() * 0.28f, rect.width() * 0.84f, dp(17f), Color.WHITE, 2, true)
            drawWrappedText(canvas, evolution.description, rect.centerX(), rect.top + rect.height() * 0.58f, rect.width() * 0.82f, dp(10f), Color.rgb(169, 187, 174), 3)
            drawCenteredText(canvas, "SPEND 1 CORE", rect.centerX(), rect.bottom - rect.height() * 0.12f, dp(9f), Color.rgb(255, 203, 81), true)
        }
    }

    private fun drawWorkshop(canvas: Canvas) {
        drawCoverBitmap(canvas, sprites.titleBackground, RectF(0f, 0f, viewWidth, viewHeight))
        paint.color = Color.argb(176, 4, 11, 8)
        canvas.drawRect(0f, 0f, viewWidth, viewHeight, paint)
        drawUiModal(canvas, RectF(viewWidth * 0.05f, viewHeight * 0.045f, viewWidth * 0.95f, viewHeight * 0.93f), 246)
        drawUiButton(canvas, workshopBackRect, "BACK", sprites.uiIconBack, UiControlTone.SECONDARY, textSize = min(dp(8f), workshopBackRect.height() * 0.30f))
        drawBitmapCentered(canvas, sprites.uiIconCraft, viewWidth * 0.5f, viewHeight * 0.062f, min(dp(30f), viewHeight * 0.055f))
        drawCenteredText(canvas, "FORGEWORKS", viewWidth * 0.5f, viewHeight * 0.105f, min(dp(28f), viewHeight * 0.060f), Color.rgb(255, 203, 120), true, true)
        drawCenteredText(canvas, "L${workshopLevel()}  •  B$gold  •  P$salvageParts  •  G$growthEssence", viewWidth * 0.5f, viewHeight * 0.132f, dp(8.5f), Color.rgb(205, 218, 207), true)
        for ((tab, rect) in workshopTabRects) {
            val active = workshopTab == tab
            val icon = when (tab) {
                WorkshopTab.CRAFT -> sprites.uiIconCraft
                WorkshopTab.IMBUE -> sprites.uiIconImbue
                WorkshopTab.SUPPLIES -> sprites.uiIconSupplies
            }
            drawUiButton(canvas, rect, tab.name, icon, if (active) UiControlTone.PRIMARY else UiControlTone.SECONDARY, textColor = if (active) Color.rgb(239, 255, 217) else Color.WHITE, textSize = min(dp(9f), rect.height() * 0.25f))
        }
        val start = workshopPageIndex * 4
        when (workshopTab) {
            WorkshopTab.CRAFT -> CraftedItem.values().drop(start).take(4).forEachIndexed { local, item -> drawCraftCard(canvas, workshopCardRects[local], item) }
            WorkshopTab.SUPPLIES -> CraftedItem.values().drop(start).take(4).forEachIndexed { local, item -> drawSupplyCard(canvas, workshopCardRects[local], item) }
            WorkshopTab.IMBUE -> {
                val targetName = imbuementTower?.kind?.title ?: imbuementTrap?.kind?.title ?: imbuementUtility?.kind?.title
                val currentImbuement = imbuementTower?.imbuement ?: imbuementTrap?.imbuement ?: imbuementUtility?.imbuement
                if (targetName == null) {
                    drawCenteredText(canvas, "SELECT A LV 3 STRUCTURE, THEN IMBUE", viewWidth * 0.5f, viewHeight * 0.50f, dp(12f), Color.rgb(202, 216, 205), true)
                } else {
                    drawCenteredText(canvas, "${targetName.uppercase()}  •  120B + 1G + 1 SIGIL${if (currentImbuement != null) "  •  REPLACES ${currentImbuement.title.uppercase()}" else ""}", viewWidth * 0.5f, viewHeight * 0.265f, dp(9f), Color.rgb(226, 205, 250), true)
                    Imbuement.values().drop(start).take(4).forEachIndexed { local, imbuement -> drawImbuementCard(canvas, workshopCardRects[local], imbuement) }
                }
            }
        }
        val totalEntries = if (workshopTab == WorkshopTab.IMBUE) Imbuement.values().size else CraftedItem.values().size
        val pages = max(1, (totalEntries + 3) / 4)
        drawUiButton(canvas, workshopPreviousRect, "", sprites.uiIconPrevious, UiControlTone.SECONDARY)
        drawUiButton(canvas, workshopNextRect, "${workshopPageIndex + 1}/$pages", sprites.uiIconNext, UiControlTone.SECONDARY, textSize = min(dp(9f), workshopNextRect.height() * 0.25f))
        if (bannerTimer > 0f) drawCenteredText(canvas, bannerText, viewWidth * 0.5f, viewHeight * 0.94f, dp(10f), Color.rgb(220, 244, 196), true)
    }

    private fun drawCraftCard(canvas: Canvas, rect: RectF, item: CraftedItem) {
        val unlocked = workshopLevel() >= item.workshopLevel
        val blockCost = craftedBlockCost(item)
        val affordable = unlocked && gold >= blockCost && salvageParts >= item.partCost && growthEssence >= item.essenceCost && supplyCount(item) < item.maxStack
        drawUiCard(canvas, rect, active = affordable)
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
        drawUiCard(canvas, rect, active = count > 0)
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
        drawUiCard(canvas, rect, active = compatible)
        strokePaint.strokeWidth = dp(1.5f)
        strokePaint.color = if (compatible) imbuement.accent else Color.rgb(64, 72, 67)
        canvas.drawRoundRect(rect, dp(12f), dp(12f), strokePaint)
        spritePaint.alpha = if (compatible) 255 else 70
        drawBitmapCentered(canvas, sprites.imbuement(imbuement), rect.left + rect.height() * 0.22f, rect.top + rect.height() * 0.25f, rect.height() * 0.34f)
        spritePaint.alpha = 255
        drawCenteredText(canvas, imbuement.title.uppercase(), rect.centerX(), rect.top + rect.height() * 0.24f, dp(12f), if (compatible) imbuement.accent else Color.rgb(105, 116, 109), true)
        drawWrappedText(canvas, imbuement.description, rect.centerX(), rect.top + rect.height() * 0.55f, rect.width() * 0.86f, dp(8f), if (compatible) Color.rgb(179, 194, 183) else Color.rgb(103, 114, 107), 3)
        drawCenteredText(canvas, if (compatible) "BIND SIGIL" else "NO EFFECT ON TARGET", rect.centerX(), rect.top + rect.height() * 0.84f, dp(8f), if (compatible) Color.WHITE else Color.rgb(126, 137, 130), true)
    }

    private fun drawBoard(canvas: Canvas) {
        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(5, 9, 7)
        // F0: clip world draw to board viewport so chrome stays screen-fixed
        canvas.save()
        canvas.clipRect(viewportLeft, viewportTop, viewportRight, viewportBottom)
        canvas.drawRoundRect(boardLeft - dp(6f), boardTop - dp(6f), boardLeft + COLS * tileSize + dp(6f), boardTop + ROWS * tileSize + dp(6f), dp(12f), dp(12f), paint)
        drawBoardBezel(canvas)
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
            drawBitmapInRect(canvas, sprites.uiBanner, RectF(x - width * 0.5f, y - dp(9f), x + width * 0.5f, y + dp(9f)), 220)
            drawCenteredText(canvas, name, x, y, max(dp(6f), tileSize * 0.10f), Color.rgb(190, 244, 78), true)
        }
    }

    /**
     * v1.4.3 chrome: a brass bezel framing the play board so the grid reads as a
     * machined table rather than a bare rectangle. Purely decorative — drawn inside
     * the existing board clip, touches no layout or hit-testing maths.
     */
    private fun drawBoardBezel(canvas: Canvas) {
        val left = boardLeft - dp(6f)
        val top = boardTop - dp(6f)
        val right = boardLeft + COLS * tileSize + dp(6f)
        val bottom = boardTop + ROWS * tileSize + dp(6f)
        val corner = dp(12f)
        strokePaint.style = Paint.Style.STROKE
        // Outer tarnished brass rail
        strokePaint.strokeWidth = dp(3f)
        strokePaint.color = Color.argb(215, 148, 112, 52)
        canvas.drawRoundRect(left, top, right, bottom, corner, corner, strokePaint)
        // Inner highlight rail
        strokePaint.strokeWidth = dp(1.5f)
        strokePaint.color = Color.argb(150, 214, 172, 88)
        canvas.drawRoundRect(left + dp(3f), top + dp(3f), right - dp(3f), bottom - dp(3f), corner, corner, strokePaint)
        // Corner rivets
        paint.style = Paint.Style.FILL
        val inset = dp(9f)
        for (px in listOf(left + inset, right - inset)) {
            for (py in listOf(top + inset, bottom - inset)) {
                paint.color = Color.argb(230, 196, 154, 74)
                canvas.drawCircle(px, py, dp(2.6f), paint)
                paint.color = Color.argb(190, 92, 68, 30)
                canvas.drawCircle(px, py, dp(1.1f), paint)
            }
        }
        strokePaint.style = Paint.Style.STROKE
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
            // v1.4.3: ornate fantasy-machinery gear halo replaces the plain glowing circle.
            val ringFrame = ((ambientTime * 6f).toInt() % sprites.evolutionRing.frameCount)
                .coerceIn(0, sprites.evolutionRing.frameCount - 1)
            drawSpriteFrameCentered(
                canvas,
                sprites.evolutionRing,
                ringFrame,
                x,
                y,
                tileSize * (0.94f + sin(ambientTime * 3f) * 0.03f),
                ambientTime * 22f
            )
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
        // v1.4.4 Forge Overdrive — warm gold machinery glow on all towers while active
        if (overdriveActive) {
            val pulse = 0.5f + 0.5f * sin(ambientTime * 6f + tower.col * 0.5f + tower.row * 0.7f)
            paint.color = Color.argb((35 + pulse * 30).toInt().coerceIn(0, 255), 255, 200, 60)
            canvas.drawCircle(x, y, tileSize * (0.44f + pulse * 0.04f), paint)
            strokePaint.strokeWidth = tileSize * 0.03f
            strokePaint.color = Color.argb((120 + pulse * 80).toInt().coerceIn(0, 255), 255, 215, 80)
            canvas.drawCircle(x, y, tileSize * 0.46f, strokePaint)
        }
        if (tower.disabledTimer > 0f) drawHexShackle(canvas, x, y)
        drawRankDots(canvas, x, y + tileSize * 0.37f, tower.level, tower.overcharge, tower.kind.accent)
        drawImbuementGlyph(canvas, x, y, tower.imbuement)
    }

    /**
     * v1.4.3: animated arcane Hex shackle. Replaces the old flat purple disc
     * with the literal text "HEX" that both towers and Structures used to draw.
     */
    private fun drawHexShackle(canvas: Canvas, x: Float, y: Float) {
        val frames = sprites.statusHex.frameCount
        val frame = ((ambientTime * 8f).toInt() % frames).coerceIn(0, frames - 1)
        drawSpriteFrameCentered(
            canvas,
            sprites.statusHex,
            frame,
            x,
            y,
            tileSize * (0.78f + sin(ambientTime * 5f) * 0.05f),
            -ambientTime * 34f
        )
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
        if (utility.disabledTimer > 0f) drawHexShackle(canvas, x, y)
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
        if (!dying && (enemy.kind.elite || enemy.kind.boss) && enemy.stuckTimer > 0.15f) {
            val stuckRatio = (enemy.stuckTimer / ELITE_TRAP_BREAK_DELAY).coerceIn(0f, 1f)
            strokePaint.strokeWidth = max(2f, tileSize * 0.04f)
            strokePaint.color = Color.rgb(255, 166, 76)
            val ring = RectF(x - size * 0.63f, y - size * 0.63f, x + size * 0.63f, y + size * 0.63f)
            canvas.drawArc(ring, -90f, 360f * stuckRatio, false, strokePaint)
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
        // Keep the live command rail readable in combat: sprites carry resource identity while
        // text is reserved for values and the one-word state/action a player must act on.
        drawBitmapInRect(canvas, sprites.hudTopRail, RectF(0f, 0f, viewWidth, topBarHeight))
        drawTopStatus(canvas)
        drawTopStats(canvas)
        val compactControls = primaryActionRect.width() <= dp(92f)

        // These secondary controls are deliberately icon-only. Their forged glyphs are more
        // legible than repeating Reset/Reforge/Cancel copy beside the primary command.
        when {
            phase == GamePhase.REFORGE -> drawUiButton(canvas, resetPathRect, "", sprites.uiIconBack, UiControlTone.WARNING, textColor = Color.rgb(255, 225, 201))
            waveNumber == 0 && (phase == GamePhase.DIG || phase == GamePhase.BUILD) -> drawUiButton(canvas, resetPathRect, "", sprites.uiIconReset, UiControlTone.SECONDARY)
            phase == GamePhase.BUILD -> drawUiButton(canvas, resetPathRect, "", sprites.uiIconReforge, UiControlTone.ACCENT, textColor = Color.rgb(237, 217, 255))
        }
        val canStart = when {
            phase == GamePhase.BUILD && pathComplete -> true
            phase == GamePhase.REFORGE && pathComplete && effectiveReforgeCost() <= forgeCharges && reforgeRecoveryCost() <= gold && storedTraps.size + displacedReforgeTraps().size <= inventoryCapacity() -> true
            phase == GamePhase.WAVE && activeWaveNumbers.isNotEmpty() -> true
            else -> false
        }
        val actionLabel = when (phase) {
            GamePhase.DIG -> if (compactControls) "" else "ROUTE"
            // The wave stat carries the number on roomy screens; retain it only when that stat
            // contracts away on compact devices.
            GamePhase.BUILD -> if (compactControls) "W${waveNumber + 1}" else "START"
            GamePhase.REFORGE -> if (compactControls) "" else "CONFIRM"
            GamePhase.WAVE -> if (compactControls) "" else "STACK"
            else -> ""
        }
        val actionIcon = when (phase) {
            GamePhase.DIG -> sprites.uiIconRoute
            GamePhase.REFORGE -> sprites.uiIconReforge
            GamePhase.WAVE -> sprites.uiIconStack
            else -> sprites.uiIconLaunch
        }
        val actionTone = if (phase == GamePhase.WAVE) UiControlTone.ACCENT else UiControlTone.PRIMARY
        drawUiButton(
            canvas,
            primaryActionRect,
            actionLabel,
            actionIcon,
            actionTone,
            canStart,
            if (canStart) Color.rgb(240, 255, 214) else Color.rgb(124, 140, 129),
            min(dp(9f), primaryActionRect.height() * 0.26f)
        )
        drawUiButton(canvas, soundRect, "", if (audio.isEnabled()) sprites.menuIconSoundOn else sprites.menuIconSoundOff, UiControlTone.SECONDARY)
        drawUiButton(canvas, feedbackToggleRect, "", sprites.uiIconFeedback, if (feedbackEnabled) UiControlTone.SECONDARY else UiControlTone.WARNING)
        drawUiButton(canvas, pauseRect, "", sprites.uiIconPause, UiControlTone.SECONDARY)
        // v1.4.4 Forge Overdrive meter + activation button
        drawOverdriveButton(canvas)
    }

    /**
     * v1.4.4: Forge Overdrive button — shows charge progress as a radial fill ring around an
     * arcane gear icon. Pulses gold when full; glows hot while active.
     */
    private fun drawOverdriveButton(canvas: Canvas) {
        val rect = overdriveRect
        val ready = overdriveCharge >= OVERDRIVE_MAX && !overdriveActive
        val active = overdriveActive
        // Background button skin
        val tone = when {
            active -> UiControlTone.WARNING
            ready -> UiControlTone.ACCENT
            else -> UiControlTone.SECONDARY
        }
        val icon = when {
            active -> sprites.uiIconLaunch
            else -> sprites.uiIconReforge
        }
        drawUiButton(canvas, rect, "", icon, tone)

        val cx = rect.centerX()
        val cy = rect.centerY()
        val radius = min(rect.width(), rect.height()) * 0.42f

        // Charge fill arc (green→gold as it fills)
        if (!active && overdriveCharge > 0f) {
            val fillRatio = overdriveCharge / OVERDRIVE_MAX
            val fillR = (93 + fillRatio * 162).toInt().coerceIn(0, 255)
            val fillG = (220 + fillRatio * 35).toInt().coerceIn(0, 255)
            val fillB = (255 - fillRatio * 177).toInt().coerceIn(0, 255)
            strokePaint.style = Paint.Style.STROKE
            strokePaint.strokeWidth = max(2f, rect.height() * 0.08f)
            strokePaint.color = Color.argb(220, fillR, fillG, fillB)
            val ring = RectF(cx - radius, cy - radius, cx + radius, cy + radius)
            canvas.drawArc(ring, -90f, 360f * fillRatio, false, strokePaint)
        }

        // Ready pulse when full
        if (ready) {
            val pulse = 0.5f + 0.5f * sin(ambientTime * 6f)
            paint.color = Color.argb((60 + pulse * 80).toInt().coerceIn(0, 255), 255, 215, 80)
            canvas.drawCircle(cx, cy, radius * (1.05f + pulse * 0.08f), paint)
            strokePaint.style = Paint.Style.STROKE
            strokePaint.strokeWidth = max(2f, rect.height() * 0.06f)
            strokePaint.color = Color.argb(230, 255, 215, 80)
            canvas.drawCircle(cx, cy, radius * 1.12f, strokePaint)
        }

        // Active countdown ring (red→orange drain)
        if (active) {
            val remaining = overdriveTimer / OVERDRIVE_DURATION
            strokePaint.style = Paint.Style.STROKE
            strokePaint.strokeWidth = max(2.5f, rect.height() * 0.09f)
            strokePaint.color = Color.argb(240, 255, (100 + remaining * 100).toInt().coerceIn(0, 255), 40)
            val ring = RectF(cx - radius, cy - radius, cx + radius, cy + radius)
            canvas.drawArc(ring, -90f, 360f * remaining, false, strokePaint)
            // Hot glow
            val hotPulse = 0.5f + 0.5f * sin(ambientTime * 8f)
            paint.color = Color.argb((30 + hotPulse * 40).toInt().coerceIn(0, 255), 255, 140, 40)
            canvas.drawCircle(cx, cy, radius * 0.70f, paint)
        }

        // Charge label
        val labelSize = min(dp(7f), rect.height() * 0.18f)
        val label = when {
            active -> "${overdriveTimer.toInt()}S"
            ready -> "GO"
            else -> "${overdriveCharge.toInt()}"
        }
        val labelColor = when {
            active -> Color.rgb(255, 200, 80)
            ready -> Color.rgb(255, 215, 80)
            else -> Color.rgb(180, 200, 190)
        }
        drawCenteredText(canvas, label, cx, cy + radius + labelSize * 0.8f, labelSize, labelColor, true)
    }

    /** The left status group carries only phase, Forge Charges, and Evolution Cores. */
    private fun drawTopStatus(canvas: Canvas) {
        val left = dp(8f)
        val right = topStatusRight()
        val available = right - left
        if (available <= dp(36f)) return
        val compact = available < dp(135f)
        val ultraCompact = available < dp(90f)
        val phaseIconSize = min(if (ultraCompact) dp(14f) else if (compact) dp(16f) else dp(20f), topBarHeight * 0.31f)
        drawBitmapCentered(canvas, phaseStatusIcon(), left + phaseIconSize * 0.55f, topBarHeight * 0.31f, phaseIconSize)
        if (!ultraCompact) {
            drawText(
                canvas,
                compactPhaseLabel(),
                left + phaseIconSize + dp(4f),
                topBarHeight * 0.38f,
                min(if (compact) dp(8.5f) else dp(10.5f), topBarHeight * 0.21f),
                Color.rgb(236, 244, 228),
                Paint.Align.LEFT,
                true,
                true
            )
        }

        val iconSize = min(if (ultraCompact) dp(13f) else if (compact) dp(16f) else dp(19f), topBarHeight * 0.28f)
        val textSize = min(if (ultraCompact) dp(7f) else if (compact) dp(8f) else dp(9.5f), topBarHeight * 0.17f)
        val entries = listOf(
            Pair(sprites.uiIconReforge, formatNumber(forgeCharges)),
            Pair(sprites.uiIconEvolve, formatNumber(evolutionCores))
        )
        val gap = if (ultraCompact) dp(3f) else dp(7f)
        val total = entries.fold(gap * (entries.size - 1)) { totalWidth, entry ->
            totalWidth + spriteValueWidth(iconSize, entry.second, textSize)
        }
        var x = left + max(0f, (available - total) * 0.5f)
        val y = topBarHeight * 0.72f
        entries.forEachIndexed { index, entry ->
            x = drawSpriteValue(canvas, entry.first, entry.second, x, y, iconSize, textSize, if (index == 0) Color.rgb(255, 204, 102) else Color.rgb(219, 182, 255))
            if (index < entries.lastIndex) x += gap
        }
    }

    private fun topStatusRight(): Float {
        // On compact landscape screens reserve room for the Blocks + heart/Core pair first.
        val desired = if (viewWidth < dp(520f)) dp(82f) else max(dp(104f), min(viewWidth * 0.22f, dp(210f)))
        return min(desired, resetPathRect.left - dp(6f))
    }

    private fun phaseStatusIcon(): Bitmap = when (phase) {
        GamePhase.DIG -> sprites.uiIconRoute
        GamePhase.BUILD -> sprites.uiIconLaunch
        GamePhase.REFORGE -> sprites.uiIconReforge
        GamePhase.WAVE -> sprites.uiIconWave
        GamePhase.PAUSED -> sprites.uiIconPause
        GamePhase.PERK_DRAFT, GamePhase.EVOLUTION_DRAFT -> sprites.uiIconEvolve
        else -> sprites.uiIconLaunch
    }

    private fun compactPhaseLabel(): String = when (phase) {
        GamePhase.DIG -> "ROUTE"
        GamePhase.BUILD -> "BUILD"
        GamePhase.REFORGE -> "REFORGE"
        GamePhase.PERK_DRAFT -> "PERKS"
        GamePhase.EVOLUTION_DRAFT -> "EVOLVE"
        GamePhase.WAVE -> if (activeWaveNumbers.size > 1) "WAVE ×${activeWaveNumbers.size}" else "WAVE"
        GamePhase.PAUSED -> "PAUSED"
        else -> "READY"
    }

    /** Draws a sprite counter and returns the x coordinate immediately after it. */
    private fun drawSpriteValue(canvas: Canvas, icon: Bitmap, value: String, x: Float, centerY: Float, iconSize: Float, textSize: Float, color: Int): Float {
        drawBitmapCentered(canvas, icon, x + iconSize * 0.5f, centerY, iconSize)
        val textLeft = x + iconSize + dp(3f)
        drawText(canvas, value, textLeft, centerY + textSize * 0.34f, textSize, color, Paint.Align.LEFT, true, true)
        return textLeft + spriteValueTextWidth(value, textSize)
    }

    private fun spriteValueWidth(iconSize: Float, value: String, textSize: Float): Float =
        iconSize + dp(3f) + spriteValueTextWidth(value, textSize)

    private fun spriteValueTextWidth(value: String, textSize: Float): Float {
        paint.textSize = textSize
        paint.typeface = blackTypeface
        return paint.measureText(value)
    }

    /**
     * Fit the live resource chips between the compact phase/readout group and right-side actions.
     * Blocks, Core, and Wave remain visible in that order; Core uses its own heart sprite and a
     * short label instead of a wordy health line.
     */
    private fun drawTopStats(canvas: Canvas) {
        val left = topStatusRight()
        val right = resetPathRect.left - dp(6f)
        val available = right - left
        val gap = min(dp(7f), max(dp(3f), available * 0.025f))
        val minWidth = dp(42f)
        val count = when {
            available >= minWidth * 3f + gap * 2f -> 3
            available >= minWidth * 2f + gap -> 2
            available >= minWidth -> 1
            else -> 0
        }
        if (count == 0) return
        val width = min(dp(88f), (available - gap * (count - 1)).coerceAtLeast(minWidth) / count)
        fun statRect(index: Int): RectF {
            val statLeft = left + index * (width + gap)
            return RectF(statLeft, dp(7f), statLeft + width, topBarHeight - dp(7f))
        }
        drawStat(canvas, statRect(0), sprites.uiIconBlocks, formatNumber(gold), Color.rgb(190, 244, 78), goldPulse)
        if (count >= 2) drawStat(canvas, statRect(1), sprites.uiIconHeart, "$lives/$maxCore", Color.rgb(255, 111, 100), label = "CORE")
        if (count >= 3) {
            val displayedWave = if (phase == GamePhase.BUILD || phase == GamePhase.DIG || phase == GamePhase.REFORGE) waveNumber + 1 else waveNumber
            drawStat(canvas, statRect(2), sprites.uiIconWave, displayedWave.toString(), Color.rgb(93, 220, 255))
        }
    }

    private fun drawStat(canvas: Canvas, rect: RectF, icon: Bitmap, value: String, accent: Int, pulse: Float = 0f, label: String = "") {
        drawBitmapInRect(canvas, sprites.uiStatFrame, rect)
        val iconSize = min(rect.height() * 0.57f, rect.width() * 0.35f)
        drawBitmapCentered(canvas, icon, rect.left + rect.width() * 0.25f, rect.centerY(), iconSize)
        if (pulse > 0.02f) {
            paint.color = Color.argb((pulse * 70f).toInt().coerceIn(0, 255), Color.red(accent), Color.green(accent), Color.blue(accent))
            canvas.drawCircle(rect.centerX(), rect.centerY(), rect.height() * 0.44f * pulse, paint)
        }
        val valueX = rect.left + rect.width() * 0.66f
        if (label.isNotEmpty()) {
            val labelSize = min(min(dp(6.5f), rect.height() * 0.15f), rect.width() * 0.15f)
            val valueSize = min(min(dp(14f), topBarHeight * 0.28f), rect.width() * 0.21f)
            drawCenteredText(canvas, label, valueX, rect.centerY() - rect.height() * 0.17f, labelSize, Color.rgb(255, 214, 205), true)
            drawCenteredText(canvas, value, valueX, rect.centerY() + rect.height() * 0.17f, valueSize, accent, true, true)
        } else {
            val valueSize = min(min(dp(15f), topBarHeight * 0.30f), rect.width() * 0.25f) * (1f + pulse * 0.18f)
            drawCenteredText(canvas, value, valueX, rect.centerY(), valueSize, accent, true, true)
        }
    }

    private fun drawBottomBar(canvas: Canvas) {
        val shelfBounds = RectF(0f, viewHeight - bottomBarHeight, viewWidth, viewHeight)
        val showsInspection = (selectedCorruption != null || selectedUtility != null || selectedTower != null || selectedTrap != null) &&
            (phase == GamePhase.BUILD || phase == GamePhase.WAVE)
        when {
            phase == GamePhase.DIG || phase == GamePhase.REFORGE -> {
                drawBitmapInRect(canvas, sprites.hudBottomRail, shelfBounds)
                drawPathForgePanel(canvas)
            }
            selectedCorruption != null && showsInspection -> {
                drawBitmapInRect(canvas, sprites.hudBottomRail, shelfBounds)
                drawCorruptionPanel(canvas)
            }
            selectedUtility != null && showsInspection -> {
                drawBitmapInRect(canvas, sprites.hudBottomRail, shelfBounds)
                drawUtilityPanel(canvas)
            }
            (selectedTower != null || selectedTrap != null) && showsInspection -> {
                drawBitmapInRect(canvas, sprites.hudBottomRail, shelfBounds)
                drawDefensePanel(canvas)
            }
            else -> {
                // The rail and its build cards are one placement shelf. Translating both—not the
                // inspection surface above—makes the wave transition unambiguous.
                canvas.save()
                canvas.translate(0f, buildShelfOffsetY())
                drawBitmapInRect(canvas, sprites.hudBottomRail, shelfBounds)
                drawToolBar(canvas)
                canvas.restore()
            }
        }
    }

    private fun drawPathForgePanel(canvas: Canvas) {
        val top = viewHeight - bottomBarHeight + dp(13f)
        val bottom = viewHeight - dp(13f)
        val width = min(viewWidth - dp(28f), dp(650f))
        val left = (viewWidth - width) * 0.5f
        val reforging = phase == GamePhase.REFORGE
        drawUiPanel(canvas, left, top, left + width, bottom, active = reforging)
        drawBitmapCentered(canvas, sprites.uiIconRoute, left + width * 0.13f, (top + bottom) * 0.5f, min(bottom - top, dp(58f)))
        drawCenteredText(canvas, if (reforging) "REFORGE ROUTE" else "DRAW ROUTE", left + width * 0.45f, top + (bottom - top) * 0.37f, min(dp(15f), bottomBarHeight * 0.16f), Color.rgb(232, 248, 212), true, true)
        val routeDetail = if (reforging) {
            "F${effectiveReforgeCost()}  •  B${reforgeRecoveryCost()}  •  ${displacedReforgeTraps().size} TRAPS"
        } else {
            "DRAG  •  BACKTRACK TO UNDO"
        }
        drawCenteredText(canvas, routeDetail, left + width * 0.45f, top + (bottom - top) * 0.69f, min(dp(9f), bottomBarHeight * 0.095f), Color.rgb(190, 207, 193), true)
        drawBitmapCentered(canvas, sprites.path, left + width * 0.74f, (top + bottom) * 0.5f, min(bottom - top, dp(64f)))
        val limit = currentPathLimit()
        drawCenteredText(canvas, "${pathCells.size}/$limit", left + width * 0.89f, (top + bottom) * 0.5f, dp(13f), Color.WHITE, true)
    }

    private fun drawToolBar(canvas: Canvas) {
        val towersLocked = challengeModifier == ChallengeModifier.TRAPS_ONLY
        val trapsLocked = challengeModifier == ChallengeModifier.TOWERS_ONLY
        if (buildPage == BuildPage.INVENTORY) {
            // The three left tabs become explicit shelf selectors while Inventory is open. Tap an
            // active shelf again to return to its matching build catalog; the fourth tab pages it.
            drawPageTab(canvas, towerPageRect, "TWR ${storedTowers.size}/${inventoryCapacity()}", inventoryCategory == InventoryCategory.TOWERS, sprites.uiIconTowers)
            drawPageTab(canvas, trapPageRect, "TRP ${storedTraps.size}/${inventoryCapacity()}", inventoryCategory == InventoryCategory.TRAPS, sprites.uiIconTraps)
            drawPageTab(canvas, utilityPageRect, "STR ${storedStructures.size}/${inventoryCapacity()}", inventoryCategory == InventoryCategory.STRUCTURES, sprites.uiIconUtilities)
            drawPageTab(canvas, inventoryPageRect, "INV ${inventoryPageIndex + 1}/${inventoryPageCount()}", true, sprites.uiIconCache)
        } else {
            drawPageTab(canvas, towerPageRect, if (towersLocked) "LOCK" else "TWR ${towerPageIndex + 1}/4", buildPage == BuildPage.TOWERS, if (towersLocked) sprites.uiIconLock else sprites.uiIconTowers, towersLocked)
            drawPageTab(canvas, trapPageRect, if (trapsLocked) "LOCK" else "TRAPS", buildPage == BuildPage.TRAPS, if (trapsLocked) sprites.uiIconLock else sprites.uiIconTraps, trapsLocked)
            drawPageTab(canvas, utilityPageRect, "STR ${utilityPageIndex + 1}/5", buildPage == BuildPage.STRUCTURES, sprites.uiIconUtilities)
            drawPageTab(canvas, inventoryPageRect, "INVENTORY", false, sprites.uiIconCache)
        }
        for ((tool, rect) in toolRects) {
            val selected = selectedTool == tool && ((buildPage == BuildPage.TOWERS && tool.ordinal < BuildTool.SPIKES.ordinal) || (buildPage == BuildPage.TRAPS && tool.ordinal >= BuildTool.SPIKES.ordinal))
            val toolCost = toolPlacementCost(tool)
            val affordable = gold >= toolCost
            drawBitmapInRect(canvas, if (selected) sprites.uiBuildSlotSelected else sprites.uiBuildSlot, rect, if (affordable || selected) 255 else 205)
            drawToolIcon(canvas, tool, rect.centerX(), rect.top + rect.height() * 0.34f, min(rect.width(), rect.height()) * 0.32f)
            drawCenteredText(canvas, tool.title, rect.centerX(), rect.top + rect.height() * 0.68f, min(dp(9f), rect.width() * 0.12f), if (selected) Color.rgb(13, 22, 17) else Color.WHITE, true)
            drawCenteredText(canvas, toolCost.toString(), rect.centerX(), rect.top + rect.height() * 0.87f, min(dp(8f), rect.width() * 0.105f), if (selected) Color.rgb(22, 38, 27) else if (affordable) Color.rgb(190, 244, 78) else Color.rgb(255, 105, 94), true)
        }
        for ((kind, rect) in utilityRects) {
            val selected = selectedUtilityKind == kind
            val unlocked = utilityUnlocked(kind)
            val slotSkin = when {
                selected -> sprites.uiBuildSlotSelected
                !unlocked -> sprites.uiBuildSlotDisabled
                else -> sprites.uiBuildSlot
            }
            drawBitmapInRect(canvas, slotSkin, rect)
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
            val placedGenerators = if (kind == UtilityKind.BLOCK_GENERATOR) utilities.count { it.kind == kind } else 0
            val utilityCost = utilityPlacementCost(kind)
            val utilityFooter = when {
                !unlocked -> "WAVE 10"
                kind == UtilityKind.BLOCK_GENERATOR -> "$utilityCost  •  $placedGenerators/$MAX_BLOCK_GENERATORS"
                else -> utilityCost.toString()
            }
            val footerColor = when {
                !unlocked -> Color.rgb(255, 111, 100)
                kind == UtilityKind.BLOCK_GENERATOR && placedGenerators >= MAX_BLOCK_GENERATORS -> Color.rgb(255, 111, 100)
                gold < utilityCost -> Color.rgb(255, 111, 100)
                else -> Color.rgb(190, 244, 78)
            }
            drawCenteredText(canvas, utilityFooter, rect.centerX(), rect.top + rect.height() * 0.88f, min(dp(8f), rect.width() * 0.09f), if (selected) Color.rgb(12, 22, 17) else footerColor, true)
        }
        for ((selection, rect) in inventoryRects) {
            val selected = selectedInventorySelection == selection
            drawBitmapInRect(canvas, if (selected) sprites.uiBuildSlotSelected else sprites.uiBuildSlot, rect)
            when (selection.category) {
                InventoryCategory.TOWERS -> storedTowers.getOrNull(selection.index)?.let { stored ->
                    drawToolIcon(canvas, buildToolForTowerKind(stored.kind), rect.centerX(), rect.top + rect.height() * 0.34f, min(rect.width(), rect.height()) * 0.32f)
                }
                InventoryCategory.TRAPS -> storedTraps.getOrNull(selection.index)?.let { stored ->
                    drawSpriteFrameCentered(canvas, sprites.trap(stored.kind), 0, rect.centerX(), rect.top + rect.height() * 0.34f, min(rect.width(), rect.height()) * 0.50f)
                }
                InventoryCategory.STRUCTURES -> storedStructures.getOrNull(selection.index)?.let { stored ->
                    drawSpriteFrameCentered(canvas, sprites.utility(stored.kind), 1, rect.centerX(), rect.top + rect.height() * 0.34f, min(rect.width(), rect.height()) * 0.54f)
                }
            }
            drawCenteredText(canvas, inventoryItemTitle(selection).uppercase(), rect.centerX(), rect.top + rect.height() * 0.69f, min(dp(8f), rect.width() * 0.09f), if (selected) Color.rgb(13, 22, 17) else Color.WHITE, true)
            val stateColor = inventoryItemImbuement(selection)?.accent ?: Color.rgb(190, 244, 78)
            drawCenteredText(canvas, "FREE  •  ${inventoryItemRank(selection)}", rect.centerX(), rect.top + rect.height() * 0.87f, min(dp(7f), rect.width() * 0.08f), if (selected) Color.rgb(22, 38, 27) else stateColor, true)
        }
        if (buildPage == BuildPage.INVENTORY && inventoryCount(inventoryCategory) == 0) {
            val hint = "${inventoryCategory.title} INVENTORY EMPTY  •  STORE A PLACED ${if (inventoryCategory == InventoryCategory.STRUCTURES) "STRUCTURE" else inventoryCategory.title.dropLast(1)}"
            drawCenteredText(canvas, hint, (inventoryPageRect.right + viewWidth) * 0.5f, viewHeight - bottomBarHeight * 0.5f, dp(10f), Color.rgb(137, 153, 142), true)
        }
    }

    private fun buildToolForTowerKind(kind: TowerKind): BuildTool = when (kind) {
        TowerKind.BOLT -> BuildTool.BOLT
        TowerKind.FROST -> BuildTool.FROST
        TowerKind.CANNON -> BuildTool.CANNON
        TowerKind.EMBER -> BuildTool.EMBER
        TowerKind.BEACON -> BuildTool.BEACON
        TowerKind.THORN -> BuildTool.THORN
        TowerKind.LANCE -> BuildTool.LANCE
        TowerKind.MIRE -> BuildTool.MIRE
        TowerKind.GALE -> BuildTool.GALE
        TowerKind.SUNFORGE -> BuildTool.SUNFORGE
        TowerKind.LODESTONE -> BuildTool.LODESTONE
        TowerKind.HOWL -> BuildTool.HOWL
        TowerKind.VITRIOL -> BuildTool.VITRIOL
        TowerKind.GRAVEBOLT -> BuildTool.GRAVEBOLT
        TowerKind.AEGIS_LOOM -> BuildTool.AEGIS_LOOM
    }

    private fun drawPageTab(canvas: Canvas, rect: RectF, label: String, selected: Boolean, icon: Bitmap, locked: Boolean = false) {
        val skin = if (selected && !locked) sprites.uiTabSelected else sprites.uiTab
        drawBitmapInRect(canvas, skin, rect, if (locked) 160 else 255)
        spritePaint.alpha = if (locked) 92 else 255
        drawBitmapCentered(canvas, icon, rect.centerX(), rect.top + rect.height() * 0.34f, min(rect.width(), rect.height()) * 0.38f)
        spritePaint.alpha = 255
        drawCenteredText(canvas, label, rect.centerX(), rect.top + rect.height() * 0.72f, min(dp(8f), rect.width() * 0.105f), if (selected && !locked) Color.rgb(12, 22, 17) else Color.rgb(214, 225, 216), true)
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

    private fun drawToolIcon(canvas: Canvas, tool: BuildTool, x: Float, y: Float, size: Float) {
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
        drawBitmapCentered(canvas, sprites.uiIconRoute, x, y, size * 2.1f)
    }

    private fun drawDefensePanel(canvas: Canvas) {
        val tower = selectedTower
        val trap = selectedTrap
        val title = if (tower != null && tower.evolution != null) tower.evolution!!.title else tower?.kind?.title ?: trap?.kind?.title ?: return
        var cost = tower?.upgradeCost() ?: trap?.upgradeCost() ?: 0
        if ((tower?.level ?: trap?.level ?: 1) >= 3) cost = max(1, cost - (cost * perkCount(ForgePerk.EFFICIENT_OVERCHARGE) * 0.20f).toInt())
        val canEvolve = tower?.canEvolve() == true
        val canBuy = if (canEvolve) evolutionCores > 0 else gold >= cost
        val damage = tower?.currentDamage() ?: trap?.currentDamage() ?: 0f
        val range = tower?.currentRange()
        val rank = tower?.rankLabel() ?: trap?.rankLabel().orEmpty()
        val definition = tower?.evolution?.description ?: tower?.kind?.description ?: trap?.kind?.description ?: ""
        drawUiButton(canvas, backRect, "BACK", sprites.uiIconBack, UiControlTone.SECONDARY, textSize = min(dp(8f), backRect.height() * 0.34f))
        drawUiPanel(canvas, upgradeRect, active = canBuy)
        drawBitmapCentered(canvas, if (canEvolve) sprites.uiIconEvolve else sprites.uiIconUpgrade, upgradeRect.left + upgradeRect.width() * 0.10f, upgradeRect.top + upgradeRect.height() * 0.27f, min(upgradeRect.height() * 0.24f, dp(27f)))
        val titleColor = if (canBuy) Color.rgb(238, 252, 222) else Color.rgb(150, 168, 156)
        val upgradeTitle = if (canEvolve) "EVOLVE ${title.uppercase()}" else "UPGRADE ${title.uppercase()}  •  $rank"
        drawCenteredText(canvas, upgradeTitle, upgradeRect.centerX(), upgradeRect.top + upgradeRect.height() * 0.25f, min(dp(10f), upgradeRect.width() * 0.030f), titleColor, true)
        val detail = if (canEvolve) "Choose one permanent evolution path. Stored towers keep that path." else definition
        drawWrappedText(canvas, detail, upgradeRect.centerX(), upgradeRect.top + upgradeRect.height() * 0.50f, upgradeRect.width() * 0.90f, dp(8f), titleColor, 2)
        val stats = if (canEvolve) "${evolutionCores} CORE  •  READY" else if (range != null) "DMG ${damage.toInt()}  •  RNG ${oneDecimal(range)}  •  ${cost}B" else "DMG ${damage.toInt()}  •  ${cost}B"
        drawCenteredText(canvas, stats, upgradeRect.centerX(), upgradeRect.top + upgradeRect.height() * 0.81f, min(dp(9f), upgradeRect.width() * 0.026f), titleColor, true)
        val category = if (tower != null) InventoryCategory.TOWERS else InventoryCategory.TRAPS
        val wrapped = supplyCount(CraftedItem.RECOVERY_WRAP) > 0
        val storageCost = if (wrapped) 0 else tower?.let { towerStorageCost(it) } ?: trap?.let { trapStorageCost(it) } ?: 0
        val storageEnabled = hasInventoryRoom(category) && (wrapped || gold >= storageCost)
        val storageLabel = if (wrapped) "STORE  •  WRAP FREE" else "STORE  •  ${storageCost}B"
        drawUiButton(canvas, storeRect, storageLabel, sprites.uiIconStore, UiControlTone.SECONDARY, storageEnabled, if (storageEnabled) Color.rgb(190, 220, 240) else Color.rgb(138, 151, 142), min(dp(7.5f), storeRect.height() * 0.34f))
        val existingImbuement = tower?.imbuement ?: trap?.imbuement
        drawUiButton(canvas, imbueRect, if (existingImbuement == null) "IMBUE" else "IMBUED  •  ${existingImbuement.title.uppercase()}", sprites.uiIconImbue, UiControlTone.ACCENT, textColor = existingImbuement?.accent ?: Color.rgb(232, 216, 255), textSize = min(dp(7.5f), imbueRect.height() * 0.34f))
        val recycleLocked = challengeModifier == ChallengeModifier.NO_RECYCLING
        drawUiButton(canvas, sellRect, if (recycleLocked) "NO RECYCLING" else "RECYCLE  •  +${tower?.sellValue(recyclingMultiplier()) ?: trap?.sellValue(recyclingMultiplier()) ?: 0}", sprites.uiIconRecycle, UiControlTone.WARNING, !recycleLocked, Color.rgb(255, 217, 193), min(dp(7.5f), sellRect.height() * 0.34f))
    }

    private fun drawUtilityPanel(canvas: Canvas) {
        val utility = selectedUtility ?: return
        val cost = if (utility.level < utility.maxLevel()) utility.upgradeCost() else 0
        val canUpgrade = utility.level < utility.maxLevel() && gold >= cost
        val structureTextColor = if (canUpgrade) Color.rgb(238, 252, 222) else Color.rgb(190, 204, 194)
        drawUiButton(canvas, backRect, "BACK", sprites.uiIconBack, UiControlTone.SECONDARY, textSize = min(dp(8f), backRect.height() * 0.34f))
        drawUiPanel(canvas, upgradeRect, active = canUpgrade)
        drawBitmapCentered(canvas, sprites.uiIconUpgrade, upgradeRect.left + upgradeRect.width() * 0.10f, upgradeRect.top + upgradeRect.height() * 0.27f, min(upgradeRect.height() * 0.24f, dp(27f)))
        drawCenteredText(canvas, "${utility.kind.title.uppercase()}  •  LV ${utility.level}", upgradeRect.centerX(), upgradeRect.top + upgradeRect.height() * 0.25f, min(dp(10f), upgradeRect.width() * 0.03f), structureTextColor, true)
        val definition = if (utility.kind == UtilityKind.BLOCK_GENERATOR) {
            "+${utility.blockOutput()} BLOCKS AFTER EACH WAVE"
        } else {
            utility.kind.description
        }
        drawWrappedText(canvas, definition, upgradeRect.centerX(), upgradeRect.top + upgradeRect.height() * 0.51f, upgradeRect.width() * 0.90f, dp(8f), structureTextColor, 2)
        val upgradeFooter = if (utility.level < utility.maxLevel()) "UPGRADE  •  ${cost}B" else "MAX LEVEL"
        if (utility.kind == UtilityKind.BLOCK_GENERATOR) {
            val activeGenerators = utilities.count { it.kind == UtilityKind.BLOCK_GENERATOR }
            drawCenteredText(canvas, "$activeGenerators/$MAX_BLOCK_GENERATORS ACTIVE  •  $upgradeFooter", upgradeRect.centerX(), upgradeRect.top + upgradeRect.height() * 0.82f, min(dp(8f), upgradeRect.width() * 0.026f), structureTextColor, true)
        } else {
            drawCenteredText(canvas, upgradeFooter, upgradeRect.centerX(), upgradeRect.top + upgradeRect.height() * 0.82f, min(dp(8f), upgradeRect.width() * 0.026f), structureTextColor, true)
        }
        val wrapped = supplyCount(CraftedItem.RECOVERY_WRAP) > 0
        val storageCost = if (wrapped) 0 else structureStorageCost(utility)
        val storageEnabled = canStoreStructure(utility) && (wrapped || gold >= storageCost)
        val storageLabel = if (wrapped) "STORE  •  WRAP FREE" else "STORE  •  ${storageCost}B"
        val workshop = utility.kind == UtilityKind.FORGE_WORKSHOP
        if (workshop) {
            drawUiButton(canvas, workshopRect, "FORGEWORKS", sprites.uiIconStore, UiControlTone.PRIMARY, textColor = Color.rgb(239, 250, 211), textSize = min(dp(6.8f), workshopRect.height() * 0.31f))
            drawUiButton(canvas, structureStoreRect, storageLabel, sprites.uiIconStore, UiControlTone.SECONDARY, storageEnabled, if (storageEnabled) Color.rgb(190, 220, 240) else Color.rgb(138, 151, 142), min(dp(6.4f), structureStoreRect.height() * 0.30f))
        } else {
            drawUiButton(canvas, storeRect, storageLabel, sprites.uiIconStore, UiControlTone.SECONDARY, storageEnabled, if (storageEnabled) Color.rgb(190, 220, 240) else Color.rgb(138, 151, 142), min(dp(7.5f), storeRect.height() * 0.34f))
        }
        drawUiButton(canvas, imbueRect, if (utility.imbuement == null) "IMBUE" else "IMBUED  •  ${utility.imbuement!!.title.uppercase()}", sprites.uiIconImbue, UiControlTone.ACCENT, textColor = utility.imbuement?.accent ?: Color.rgb(232, 216, 255), textSize = min(dp(7.5f), imbueRect.height() * 0.34f))
        val recycleLocked = challengeModifier == ChallengeModifier.NO_RECYCLING
        drawUiButton(canvas, sellRect, if (recycleLocked) "NO RECYCLING" else "RECYCLE", sprites.uiIconRecycle, UiControlTone.WARNING, !recycleLocked, Color.rgb(255, 217, 193), min(dp(7.5f), sellRect.height() * 0.34f))
    }

    private fun drawCorruptionPanel(canvas: Canvas) {
        val corruption = selectedCorruption ?: return
        val cost = corruptionCleanseCost(corruption)
        val vial = corruptionUsesVial(corruption)
        val canCleanse = forgeCharges >= cost
        drawUiButton(canvas, backRect, "BACK", sprites.uiIconBack, UiControlTone.SECONDARY, textSize = min(dp(8f), backRect.height() * 0.34f))
        drawUiPanel(canvas, upgradeRect, active = canCleanse)
        drawBitmapCentered(canvas, sprites.uiIconRecycle, upgradeRect.left + upgradeRect.width() * 0.10f, upgradeRect.top + upgradeRect.height() * 0.31f, min(upgradeRect.height() * 0.24f, dp(27f)))
        drawCenteredText(canvas, "CLEANSE ${corruption.kind.title.uppercase()}", upgradeRect.centerX(), upgradeRect.top + upgradeRect.height() * 0.37f, dp(10f), if (canCleanse) Color.rgb(240, 252, 227) else Color.rgb(177, 188, 180), true)
        drawWrappedText(canvas, corruption.kind.description, upgradeRect.centerX(), upgradeRect.top + upgradeRect.height() * 0.69f, upgradeRect.width() * 0.90f, dp(8f), Color.rgb(224, 230, 225), 2)
        drawUiPanel(canvas, sellRect, active = canCleanse)
        drawBitmapCentered(canvas, sprites.uiIconImbue, sellRect.left + sellRect.width() * 0.18f, sellRect.centerY(), min(sellRect.height() * 0.52f, dp(22f)))
        drawCenteredText(canvas, "${cost} FORGE${if (vial) " + VIAL" else ""}", sellRect.left + sellRect.width() * 0.60f, sellRect.top + sellRect.height() * 0.40f, dp(10f), Color.rgb(232, 216, 255), true)
        drawCenteredText(canvas, "YOU HAVE $forgeCharges", sellRect.left + sellRect.width() * 0.60f, sellRect.top + sellRect.height() * 0.70f, dp(8f), Color.rgb(190, 174, 208), true)
    }

    private fun drawBanner(canvas: Canvas) {
        if (!feedbackEnabled) return
        val timed = bannerTimer > 0f
        // Persistent copy is intentionally sparse. The rail already identifies the phase and
        // resources, so this panel is reserved for an active timer, route costs, survey intel,
        // or a short-lived gameplay result/error.
        val instruction = when {
            timed -> bannerText
            phase == GamePhase.DIG -> "ROUTE  ${pathCells.size}/${currentPathLimit()}"
            phase == GamePhase.REFORGE -> "${effectiveReforgeCost()} FORGE  •  ${reforgeRecoveryCost()} BLOCKS"
            phase == GamePhase.BUILD && nextWaveTimer > 0f -> "W${waveNumber + 1}  •  ${nextWaveCountdownSeconds()}S"
            phase == GamePhase.BUILD && surveyAvailable() -> surveyPreviewText(waveNumber + 1)
            else -> ""
        }
        if (instruction.isEmpty()) return
        val elapsed = if (timed) (bannerDuration - bannerTimer).coerceAtLeast(0f) else 0f
        val enter = if (timed) (elapsed / 0.22f).coerceIn(0f, 1f) else 1f
        val enterEase = 1f - (1f - enter) * (1f - enter)
        val fadeOut = if (timed && bannerTimer in 0f..0.38f) (bannerTimer / 0.38f) else 1f
        val alpha = (if (timed) 235f * fadeOut else 212f).toInt().coerceIn(0, 255)
        val baseWidth = min(viewWidth * 0.31f, dp(290f))
        val baseHeight = min(dp(54f), max(dp(42f), tileSize * 0.78f))
        val widthScale = if (timed) 0.82f + 0.18f * enterEase else 1f
        val heightScale = if (timed) 0.88f + 0.20f * enterEase else 1f
        val width = baseWidth * widthScale
        val height = baseHeight * heightScale
        val left = dp(12f)
        val top = topBarHeight + dp(9f) - (1f - enterEase) * dp(8f)
        drawBitmapInRect(canvas, sprites.uiBanner, RectF(left, top, left + width, top + height), alpha)
        val icon = when (phase) {
            GamePhase.DIG, GamePhase.REFORGE -> sprites.uiIconRoute
            GamePhase.WAVE -> sprites.uiIconStack
            else -> sprites.uiIconLaunch
        }
        spritePaint.alpha = alpha
        drawBitmapCentered(canvas, icon, left + width * 0.105f, top + height * 0.47f, min(height * 0.52f, dp(26f)))
        spritePaint.alpha = 255
        val textSize = min(dp(9f), height * 0.24f) * (if (timed) 0.92f + 0.12f * enterEase else 1f)
        drawWrappedText(
            canvas,
            instruction,
            left + width * 0.56f,
            top + height * 0.47f,
            width * 0.77f,
            textSize,
            Color.argb(min(255, alpha + 25), 234, 240, 226),
            2,
            true
        )
        if (timed && bannerDuration > 0.01f) {
            val progress = (bannerTimer / bannerDuration).coerceIn(0f, 1f)
            paint.color = Color.argb((alpha * 0.85f).toInt().coerceIn(0, 255), 190, 244, 78)
            canvas.drawRect(left + dp(18f), top + height - dp(4f), left + dp(18f) + (width - dp(36f)) * progress, top + height - dp(2f), paint)
        }
    }

    private fun drawPauseOverlay(canvas: Canvas) {
        paint.color = Color.argb(218, 7, 13, 10)
        canvas.drawRect(0f, 0f, viewWidth, viewHeight, paint)
        drawUiModal(canvas, RectF(viewWidth * 0.18f, viewHeight * 0.18f, viewWidth * 0.82f, viewHeight * 0.84f), 248)
        drawBitmapCentered(canvas, sprites.uiIconPause, viewWidth * 0.5f, viewHeight * 0.28f, min(dp(46f), viewHeight * 0.09f))
        drawCenteredText(canvas, "RUN PAUSED", viewWidth * 0.5f, viewHeight * 0.37f, min(dp(37f), viewHeight * 0.085f), Color.WHITE, true, true)
        val note = if (phaseBeforePause == GamePhase.WAVE) "RESUME WAVE  •  MENU RETURNS TO CHECKPOINT" else "PROGRESS SAVED"
        drawCenteredText(canvas, note, viewWidth * 0.5f, viewHeight * 0.46f, dp(11f), Color.rgb(192, 207, 195), true)
        drawEndButtons(canvas, "RESUME", "MENU")
    }

    private fun drawEndOverlay(canvas: Canvas) {
        paint.color = Color.argb(226, 7, 13, 10)
        canvas.drawRect(0f, 0f, viewWidth, viewHeight, paint)
        drawUiModal(canvas, RectF(viewWidth * 0.15f, viewHeight * 0.14f, viewWidth * 0.85f, viewHeight * 0.88f), 248)
        val victory = phase == GamePhase.VICTORY
        val accent = if (victory) Color.rgb(190, 244, 78) else Color.rgb(255, 102, 92)
        drawBitmapCentered(canvas, if (victory) sprites.uiIconCore else sprites.uiIconRecycle, viewWidth * 0.5f, viewHeight * 0.20f, min(dp(44f), viewHeight * 0.08f))
        drawCenteredText(canvas, if (victory) "CORE SECURED" else "CORE BREACHED", viewWidth * 0.5f, viewHeight * 0.28f, min(dp(48f), viewHeight * 0.105f), accent, true, true)
        drawCenteredText(canvas, if (victory) "RUN COMPLETE" else "HIGH WAVE RECORDED", viewWidth * 0.5f, viewHeight * 0.39f, dp(12f), Color.rgb(202, 214, 204), true)
        val cardWidth = min(dp(400f), viewWidth * 0.60f)
        val cardLeft = (viewWidth - cardWidth) * 0.5f
        val cardTop = viewHeight * 0.47f
        val cardBottom = viewHeight * 0.63f
        drawUiPanel(canvas, cardLeft, cardTop, cardLeft + cardWidth, cardBottom, active = victory)
        drawResultStat(canvas, cardLeft + cardWidth * 0.18f, cardTop, cardBottom, "SCORE", formatNumber(score), accent)
        drawResultStat(canvas, cardLeft + cardWidth * 0.50f, cardTop, cardBottom, "WAVE", waveNumber.toString(), Color.rgb(93, 220, 255))
        val modeBest = when (gameMode) { GameMode.ENDLESS -> bestWave; GameMode.DAILY -> bestDailyWave; GameMode.CUSTOM -> bestCustomWave }
        drawResultStat(canvas, cardLeft + cardWidth * 0.82f, cardTop, cardBottom, "BEST", modeBest.toString(), Color.rgb(255, 188, 96))
        drawEndButtons(canvas, if (gameMode == GameMode.ENDLESS) "NEW RUN" else "RETRY", "MENU")
    }

    private fun drawResultStat(canvas: Canvas, x: Float, top: Float, bottom: Float, label: String, value: String, color: Int) {
        drawCenteredText(canvas, label, x, top + (bottom - top) * 0.36f, dp(9f), Color.rgb(118, 137, 124), true)
        drawCenteredText(canvas, value, x, top + (bottom - top) * 0.68f, dp(19f), color, true, true)
    }

    private fun drawEndButtons(canvas: Canvas, primary: String, secondary: String) {
        drawUiButton(canvas, endPrimaryRect, primary, sprites.uiIconLaunch, UiControlTone.PRIMARY, textColor = Color.rgb(240, 255, 214), textSize = min(dp(12f), endPrimaryRect.height() * 0.30f))
        drawUiButton(canvas, endSecondaryRect, secondary, sprites.uiIconBack, UiControlTone.SECONDARY, textSize = min(dp(12f), endSecondaryRect.height() * 0.30f))
    }

    /** Draw a menu/environment bitmap into a responsive rectangle without changing its crop ratio. */
    private fun drawCoverBitmap(canvas: Canvas, bitmap: Bitmap, destination: RectF) {
        val sourceAspect = bitmap.width.toFloat() / max(1, bitmap.height).toFloat()
        val destinationAspect = destination.width() / max(1f, destination.height())
        val source = if (sourceAspect > destinationAspect) {
            val width = (bitmap.height * destinationAspect).toInt().coerceIn(1, bitmap.width)
            val left = (bitmap.width - width) / 2
            Rect(left, 0, left + width, bitmap.height)
        } else {
            val height = (bitmap.width / destinationAspect).toInt().coerceIn(1, bitmap.height)
            val top = (bitmap.height - height) / 2
            Rect(0, top, bitmap.width, top + height)
        }
        canvas.drawBitmap(bitmap, source, destination, spritePaint)
    }

    /** Draw an authored PNG skin at the supplied hit rectangle, retaining any alpha in the sprite. */
    private fun drawBitmapInRect(canvas: Canvas, bitmap: Bitmap, destination: RectF, alpha: Int = 255) {
        val previousAlpha = spritePaint.alpha
        spritePaint.alpha = alpha.coerceIn(0, 255)
        canvas.drawBitmap(bitmap, null, destination, spritePaint)
        spritePaint.alpha = previousAlpha
    }

    /** Shared sprite surfaces replace the former flat rounded Canvas panels throughout live play. */
    private fun drawUiPanel(canvas: Canvas, rect: RectF, active: Boolean = false, alpha: Int = 255) {
        drawBitmapInRect(canvas, if (active) sprites.uiPanelActive else sprites.uiPanel, rect, alpha)
    }

    private fun drawUiPanel(canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float, active: Boolean = false, alpha: Int = 255) {
        drawUiPanel(canvas, RectF(left, top, right, bottom), active, alpha)
    }

    private fun drawUiCard(canvas: Canvas, rect: RectF, active: Boolean = false, alpha: Int = 255) {
        drawBitmapInRect(canvas, if (active) sprites.uiCardActive else sprites.uiCard, rect, alpha)
    }

    private fun drawUiModal(canvas: Canvas, rect: RectF, alpha: Int = 255) {
        drawBitmapInRect(canvas, sprites.uiModal, rect, alpha)
    }

    /**
     * Draw a labeled, art-backed in-game control. Text stays live so costs, wave numbers, and
     * save-state labels remain accurate while the button, framing, and icon are authored sprites.
     */
    private fun drawUiButton(
        canvas: Canvas,
        rect: RectF,
        label: String,
        icon: Bitmap? = null,
        tone: UiControlTone = UiControlTone.SECONDARY,
        enabled: Boolean = true,
        textColor: Int = Color.WHITE,
        textSize: Float = min(dp(10f), rect.height() * 0.28f),
        pressed: Boolean = false
    ) {
        val held = enabled && (pressed || (uiTouchActive && rect.contains(uiTouchX, uiTouchY)))
        val skin = if (!enabled) {
            sprites.uiButtonDisabled
        } else {
            when (tone) {
                UiControlTone.PRIMARY -> if (held) sprites.uiButtonPrimaryPressed else sprites.uiButtonPrimary
                UiControlTone.SECONDARY -> if (held) sprites.uiButtonSecondaryPressed else sprites.uiButtonSecondary
                UiControlTone.ACCENT -> if (held) sprites.uiButtonAccentPressed else sprites.uiButtonAccent
                UiControlTone.WARNING -> if (held) sprites.uiButtonWarningPressed else sprites.uiButtonWarning
            }
        }
        drawBitmapInRect(canvas, skin, rect, if (enabled) 255 else 175)
        if (icon != null) {
            val iconOnly = label.isEmpty()
            val iconSize = if (iconOnly) {
                min(rect.width(), rect.height()) * 0.66f
            } else {
                min(rect.height() * 0.54f, rect.width() * 0.20f)
            }
            val iconX = if (iconOnly) rect.centerX() else rect.left + rect.width() * 0.19f
            spritePaint.alpha = if (enabled) 255 else 105
            drawBitmapCentered(canvas, icon, iconX, rect.centerY(), iconSize)
            spritePaint.alpha = 255
            if (!iconOnly) {
                drawCenteredText(canvas, label, rect.left + rect.width() * 0.60f, rect.centerY(), textSize, textColor, true)
            }
        } else {
            drawCenteredText(canvas, label, rect.centerX(), rect.centerY(), textSize, textColor, true)
        }
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
