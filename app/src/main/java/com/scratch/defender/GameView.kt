package com.scratch.defender

import android.content.Context
import android.graphics.*
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import kotlin.math.*
import kotlin.random.Random

class GameView(ctx: Context) : SurfaceView(ctx), SurfaceHolder.Callback, Runnable {

    // ---------- World constants ----------
    private val worldW = 4800f
    private val groundY get() = h - 90f
    private var w = 0f
    private var h = 0f

    // ---------- Rendering ----------
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(0x39, 0xFF, 0x14); textSize = 36f; typeface = Typeface.MONOSPACE
    }
    private val smallText = Paint(textPaint).apply { textSize = 22f }
    private val neon = Color.rgb(0x39, 0xFF, 0x14)
    private val cyan = Color.rgb(0x33, 0xCC, 0xFF)
    private val magenta = Color.rgb(0xFF, 0x33, 0x99)
    private val yellow = Color.rgb(0xFF, 0xDD, 0x33)
    private val red = Color.rgb(0xFF, 0x44, 0x44)

    // ---------- Thread ----------
    @Volatile private var running = false
    private var thread: Thread? = null

    // ---------- Game state ----------
    private var score = 0
    private var lives = 3
    private var bombs = 3
    private var wave = 1
    private var paused = false
    private var gameOver = false
    private var hyperCooldown = 0f
    private var waveMsg = ""
    private var waveMsgT = 0f

    // Cheats
    private var invincible = false
    private var rapidFire = false
    private var freezeT = 0f
    private val cheatBuf = ArrayDeque<Int>()
    private var cheatToast = ""
    private var cheatToastT = 0f

    // Terrain (jagged line)
    private val terrain = FloatArray(96) { 60f + Random.nextFloat() * 40f }

    // Ship
    private var shipX = worldW / 2f
    private var shipY = 0f
    private var vx = 0f
    private var vy = 0f
    private var facing = 1 // +1 right, -1 left
    private var cameraX = 0f

    // Entity lists
    private val bullets = ArrayList<Bullet>()
    private val eBullets = ArrayList<Bullet>()
    private val humans = ArrayList<Human>()
    private val enemies = ArrayList<Enemy>()
    private val particles = ArrayList<Particle>()

    // Input flags
    private var inLeft = false; private var inRight = false
    private var inUp = false; private var inDown = false
    private var axisX = 0f; private var axisY = 0f
    private var fireHeld = false
    private var fireCool = 0f

    init { holder.addCallback(this); isFocusable = true; isFocusableInTouchMode = true; Sfx.init() }

    override fun surfaceCreated(h0: SurfaceHolder) { resume() }
    override fun surfaceChanged(h0: SurfaceHolder, f: Int, wv: Int, hv: Int) {
        w = wv.toFloat(); h = hv.toFloat()
        if (shipY == 0f) { shipY = h / 2f; resetWorld() }
    }
    override fun surfaceDestroyed(h0: SurfaceHolder) { pause() }

    fun resume() {
        if (running) return
        running = true
        thread = Thread(this).also { it.start() }
    }
    fun pause() {
        running = false
        try { thread?.join() } catch (_: InterruptedException) {}
    }

    private fun resetWorld() {
        score = 0; lives = 3; bombs = 3; wave = 1
        gameOver = false; paused = false
        humans.clear(); enemies.clear(); bullets.clear(); eBullets.clear(); particles.clear()
        repeat(10) { humans.add(Human(Random.nextFloat() * worldW)) }
        spawnWave()
    }

    private fun spawnWave() {
        // Gentle curve: +1 lander per wave capped at 6; speed scales to ~1.3x by wave 10
        val landers = min(3 + wave / 2, 6)
        val pods = if (wave >= 2) min(wave / 2, 3) else 0
        val bombers = if (wave >= 3) 1 + wave / 4 else 0
        val baiterDelay = max(20f - wave * 0.5f, 10f) // seconds before baiters come
        repeat(landers) { enemies.add(Lander(randX(), Random.nextFloat() * h * 0.5f + 60f)) }
        repeat(pods) { enemies.add(Pod(randX(), Random.nextFloat() * h * 0.5f + 100f)) }
        repeat(bombers) { enemies.add(Bomber(randX(), Random.nextFloat() * h * 0.4f + 80f)) }
        baiterTimer = baiterDelay
        waveMsg = "WAVE $wave"
        waveMsgT = 2f
    }

    private var baiterTimer = 20f

    private fun randX(): Float {
        var x: Float; do { x = Random.nextFloat() * worldW } while (abs(x - shipX) < 300f); return x
    }

    private fun difficultyMul(): Float = min(1f + (wave - 1) * 0.03f, 1.3f)

    // ---------- Input ----------
    private val heldKeys = HashSet<Int>()
    fun handleKey(code: Int, down: Boolean): Boolean {
        val firstPress = down && !heldKeys.contains(code)
        if (down) heldKeys.add(code) else heldKeys.remove(code)
        if (firstPress) pushCheat(code)
        when (code) {
            KeyEvent.KEYCODE_DPAD_LEFT -> { inLeft = down; return true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { inRight = down; return true }
            KeyEvent.KEYCODE_DPAD_UP -> { inUp = down; return true }
            KeyEvent.KEYCODE_DPAD_DOWN -> { inDown = down; return true }
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_SPACE -> { fireHeld = down; if (firstPress) tryFire(); return true }
            KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BACK -> {
                if (firstPress) {
                    if (gameOver) { resetWorld() } else smartBomb()
                }
                return true
            }
            KeyEvent.KEYCODE_BUTTON_Y -> { if (firstPress) hyperspace(); return true }
            KeyEvent.KEYCODE_BUTTON_X -> { if (firstPress) reverseFacing(); return true }
            KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_MENU -> {
                if (firstPress) paused = !paused; return true
            }
        }
        return false
    }

    private var hatX = 0f
    private var hatY = 0f
    fun handleMotion(ev: MotionEvent): Boolean {
        val src = ev.source
        val isJoy = (src and android.view.InputDevice.SOURCE_JOYSTICK) != 0
        val isDpad = (src and android.view.InputDevice.SOURCE_DPAD) != 0
        if (!isJoy && !isDpad) return false
        axisX = ev.getAxisValue(MotionEvent.AXIS_X)
        axisY = ev.getAxisValue(MotionEvent.AXIS_Y)
        if (abs(axisX) < 0.2f) axisX = 0f
        if (abs(axisY) < 0.2f) axisY = 0f
        // HAT axes often carry the D-pad — synthesize key events on edges
        val hx = ev.getAxisValue(MotionEvent.AXIS_HAT_X)
        val hy = ev.getAxisValue(MotionEvent.AXIS_HAT_Y)
        hatEdge(hatX, hx, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT)
        hatEdge(hatY, hy, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN)
        hatX = hx; hatY = hy
        return true
    }

    private fun hatEdge(prev: Float, cur: Float, negKey: Int, posKey: Int) {
        val p = when { prev < -0.5f -> -1; prev > 0.5f -> 1; else -> 0 }
        val c = when { cur < -0.5f -> -1; cur > 0.5f -> 1; else -> 0 }
        if (p == c) return
        // Release previous
        if (p == -1) handleKey(negKey, false)
        if (p == 1) handleKey(posKey, false)
        // Press new
        if (c == -1) handleKey(negKey, true)
        if (c == 1) handleKey(posKey, true)
    }

    private fun reverseFacing() { facing = -facing }

    private fun tryFire() {
        if (fireCool > 0f || gameOver || paused) return
        bullets.add(Bullet(shipX + facing * 24f, shipY, facing * 1600f, 0f))
        fireCool = if (rapidFire) 0.05f else 0.14f
        Sfx.play("fire")
    }

    private fun smartBomb() {
        if (bombs <= 0 || gameOver) return
        bombs--
        Sfx.play("bomb")
        val it = enemies.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (abs(wrapDx(e.x, cameraX + w / 2f)) < w * 0.75f) {
                score += e.points; boom(e.x, e.y, 20); e.onDeath(this); it.remove()
            }
        }
        eBullets.clear()
    }

    private fun hyperspace() {
        if (hyperCooldown > 0f || gameOver) return
        hyperCooldown = 3f
        shipX = Random.nextFloat() * worldW
        shipY = 120f + Random.nextFloat() * (h - 240f)
        boom(shipX, shipY, 10)
        Sfx.play("hyper")
    }

    // ---------- Cheats ----------
    private val cheats = listOf(
        intArrayOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BUTTON_A) to "INVINCIBLE",
        intArrayOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BUTTON_B) to "+10 BOMBS",
        intArrayOf(KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_A) to "+3 LIVES",
        intArrayOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_BUTTON_Y) to "RAPID FIRE",
        intArrayOf(KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_B,
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_B) to "FREEZE 10s",
        intArrayOf(KeyEvent.KEYCODE_BUTTON_Y, KeyEvent.KEYCODE_BUTTON_Y,
            KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_BUTTON_X) to "SKIP WAVE"
    )

    private fun pushCheat(code: Int) {
        cheatBuf.addLast(code)
        while (cheatBuf.size > 12) cheatBuf.removeFirst()
        for ((seq, name) in cheats) {
            if (cheatBuf.size >= seq.size) {
                val tail = cheatBuf.toIntArray().copyOfRange(cheatBuf.size - seq.size, cheatBuf.size)
                if (tail.contentEquals(seq)) {
                    applyCheat(name); cheatBuf.clear(); return
                }
            }
        }
    }

    private fun applyCheat(name: String) {
        cheatToast = "CHEAT: $name"; cheatToastT = 2.5f
        when (name) {
            "INVINCIBLE" -> invincible = !invincible
            "+10 BOMBS" -> bombs += 10
            "+3 LIVES" -> lives += 3
            "RAPID FIRE" -> rapidFire = !rapidFire
            "FREEZE 10s" -> freezeT = 10f
            "SKIP WAVE" -> { enemies.clear(); wave++; spawnWave() }
        }
    }

    // ---------- Loop ----------
    override fun run() {
        var last = System.nanoTime()
        while (running) {
            try {
                val now = System.nanoTime()
                var dt = (now - last) / 1e9f
                if (dt > 0.05f) dt = 0.05f
                last = now
                if (w <= 0f || h <= 0f) { Thread.sleep(16); continue }
                if (!paused && !gameOver) update(dt)
                if (cheatToastT > 0f) cheatToastT -= dt
                if (waveMsgT > 0f) waveMsgT -= dt
                draw()
            } catch (t: Throwable) {
                android.util.Log.e("DefenderTV", "loop error", t)
                try { Thread.sleep(100) } catch (_: InterruptedException) {}
            }
        }
    }

    private fun update(dt: Float) {
        val dm = difficultyMul()
        if (freezeT > 0f) freezeT -= dt
        if (hyperCooldown > 0f) hyperCooldown -= dt
        if (fireCool > 0f) fireCool -= dt
        if (fireHeld && rapidFire) tryFire()

        // Ship control
        val ax = if (axisX != 0f) axisX else (if (inRight) 1f else 0f) + (if (inLeft) -1f else 0f)
        val ay = if (axisY != 0f) axisY else (if (inDown) 1f else 0f) + (if (inUp) -1f else 0f)
        if (ax > 0f) facing = 1 else if (ax < 0f) facing = -1
        vx += ax * 1200f * dt
        vy += ay * 900f * dt
        vx *= 0.96f; vy *= 0.90f
        vx = vx.coerceIn(-700f, 700f); vy = vy.coerceIn(-500f, 500f)
        shipX = wrap(shipX + vx * dt)
        shipY = (shipY + vy * dt).coerceIn(60f, groundY - 20f)

        // Camera lookahead
        val target = shipX - w / 2f + facing * w * 0.2f
        cameraX = wrap(cameraX + wrapDx(target, cameraX) * min(1f, dt * 4f))

        // Baiters appear after a delay
        baiterTimer -= dt
        if (baiterTimer <= 0f) {
            baiterTimer = 12f
            if (enemies.count { it is Baiter } < 2)
                enemies.add(Baiter(wrap(shipX + if (Random.nextBoolean()) 900f else -900f),
                    Random.nextFloat() * h * 0.5f + 80f))
        }

        // Enemies
        if (freezeT <= 0f) {
            for (e in enemies) e.update(dt, dm, this)
        }

        // Bullets
        val bi = bullets.iterator()
        while (bi.hasNext()) {
            val b = bi.next(); b.update(dt)
            if (b.life <= 0f) { bi.remove(); continue }
            val ei = enemies.iterator()
            while (ei.hasNext()) {
                val e = ei.next()
                if (abs(wrapDx(b.x, e.x)) < e.r && abs(b.y - e.y) < e.r) {
                    score += e.points; boom(e.x, e.y, 12)
                    if (e is Pod) {
                        repeat(4) { enemies.add(Swarmer(e.x, e.y)) }
                        Sfx.play("bigBoom")
                    } else Sfx.play("boom")
                    e.onDeath(this)
                    ei.remove(); bi.remove(); break
                }
            }
        }

        // Enemy bullets
        val ebi = eBullets.iterator()
        while (ebi.hasNext()) {
            val b = ebi.next(); b.update(dt)
            if (b.life <= 0f) { ebi.remove(); continue }
            if (!invincible && abs(wrapDx(b.x, shipX)) < 18f && abs(b.y - shipY) < 14f) {
                ebi.remove(); killShip(); break
            }
        }

        // Enemy-ship collisions
        if (!invincible) {
            for (e in enemies) if (abs(wrapDx(e.x, shipX)) < e.r + 14f && abs(e.y - shipY) < e.r + 10f) {
                killShip(); break
            }
        }

        // Humans
        for (h in humans) h.update(dt, this)
        humans.removeAll { it.state == -1 }
        enemies.removeAll { !it.alive }

        // Particles
        val pi = particles.iterator()
        while (pi.hasNext()) { val p = pi.next(); p.update(dt); if (p.life <= 0f) pi.remove() }

        if (enemies.isEmpty()) { wave++; spawnWave(); Sfx.play("wave") }
        if (humans.isEmpty()) { // all humans lost — small penalty, respawn 4 next wave only
            waveMsg = "PLANET LOST"; waveMsgT = 2f
            // mild: respawn a few humans so game continues playably
            repeat(4) { humans.add(Human(Random.nextFloat() * worldW)) }
        }
    }

    private fun killShip() {
        boom(shipX, shipY, 30)
        Sfx.play("bigBoom")
        // Drop any carried human
        for (hm in humans) if (hm.state == 3) { hm.state = 2; hm.vy = 0f }
        lives--
        shipX = worldW / 2f; shipY = h / 2f; vx = 0f; vy = 0f
        if (lives < 0) gameOver = true
    }

    fun spawnEnemyBullet(x: Float, y: Float, tx: Float, ty: Float, spd: Float) {
        val dx = wrapDx(tx, x); val dy = ty - y
        val d = max(1f, hypot(dx, dy))
        eBullets.add(Bullet(x, y, dx / d * spd, dy / d * spd, 2.5f, red))
    }

    private fun boom(x: Float, y: Float, n: Int) {
        repeat(n) {
            val a = Random.nextFloat() * 2 * PI.toFloat()
            val s = 60f + Random.nextFloat() * 200f
            particles.add(Particle(x, y, cos(a) * s, sin(a) * s))
        }
    }

    // World wrap helpers
    fun wrap(x: Float): Float { var r = x % worldW; if (r < 0) r += worldW; return r }
    fun wrapDx(a: Float, b: Float): Float {
        var d = a - b; if (d > worldW / 2f) d -= worldW; if (d < -worldW / 2f) d += worldW; return d
    }

    // ---------- Rendering ----------
    private fun draw() {
        val c = holder.lockCanvas() ?: return
        try {
            c.drawColor(Color.rgb(0, 8, 20))
            drawStars(c)
            drawTerrain(c)
            drawEntities(c)
            drawMinimap(c)
            drawHud(c)
            if (paused) centerText(c, "PAUSED")
            if (gameOver) centerText(c, "GAME OVER — press B to restart")
            if (waveMsgT > 0f) centerText(c, waveMsg, h * 0.35f)
            if (cheatToastT > 0f) {
                paint.color = yellow
                c.drawText(cheatToast, 20f, h - 20f, textPaint.also { it.color = yellow })
                textPaint.color = neon
            }
        } finally { holder.unlockCanvasAndPost(c) }
    }

    private val starSeed = LongArray(120) { Random.nextLong() }
    private fun drawStars(c: Canvas) {
        paint.color = Color.argb(140, 200, 200, 255)
        for (i in starSeed.indices) {
            val r = Random(starSeed[i])
            val sx = r.nextFloat() * worldW
            val sy = r.nextFloat() * (h - 100f)
            val px = screenX(sx)
            if (px in -4f..w + 4f) c.drawCircle(px, sy, 1.5f, paint)
        }
    }

    private fun drawTerrain(c: Canvas) {
        paint.color = neon; paint.strokeWidth = 2f; paint.style = Paint.Style.STROKE
        val path = Path()
        val step = worldW / terrain.size
        var started = false
        for (i in -1..terrain.size) {
            val idx = ((i % terrain.size) + terrain.size) % terrain.size
            val wx = i * step
            val sx = screenX(wx)
            val sy = groundY + terrain[idx] * 0.3f - 20f
            if (!started) { path.moveTo(sx, sy); started = true } else path.lineTo(sx, sy)
        }
        c.drawPath(path, paint)
        paint.style = Paint.Style.FILL
    }

    private fun drawEntities(c: Canvas) {
        // Humans
        paint.color = yellow
        for (hm in humans) {
            val x = screenX(hm.x); if (x < -20f || x > w + 20f) continue
            c.drawRect(x - 3f, hm.y - 10f, x + 3f, hm.y, paint)
            c.drawCircle(x, hm.y - 14f, 4f, paint)
        }
        // Enemies
        for (e in enemies) {
            val x = screenX(e.x); if (x < -40f || x > w + 40f) continue
            e.draw(c, this, x)
        }
        // Bullets
        paint.color = Color.WHITE
        for (b in bullets) {
            val x = screenX(b.x); c.drawRect(x - 4f, b.y - 2f, x + 4f, b.y + 2f, paint)
        }
        for (b in eBullets) {
            paint.color = b.color
            val x = screenX(b.x); c.drawCircle(x, b.y, 3f, paint)
        }
        // Particles
        for (p in particles) {
            paint.color = Color.argb((p.life * 255).toInt().coerceIn(0, 255), 255, 200, 60)
            c.drawCircle(screenX(p.x), p.y, 2f, paint)
        }
        // Ship
        drawShip(c)
    }

    private fun drawShip(c: Canvas) {
        val x = screenX(shipX); val y = shipY
        paint.color = if (invincible) magenta else cyan
        val f = facing
        val path = Path().apply {
            moveTo(x + 18f * f, y)
            lineTo(x - 14f * f, y - 10f)
            lineTo(x - 8f * f, y)
            lineTo(x - 14f * f, y + 10f)
            close()
        }
        c.drawPath(path, paint)
        // Thrust flame
        if ((vx * facing) > 50f) {
            paint.color = yellow
            val fx = x - 14f * f
            c.drawCircle(fx - 4f * f, y, 4f, paint)
        }
    }

    private fun drawMinimap(c: Canvas) {
        val mw = w * 0.6f; val mx = (w - mw) / 2f; val my = 10f; val mh = 50f
        paint.color = Color.argb(140, 0, 20, 40); paint.style = Paint.Style.FILL
        c.drawRect(mx, my, mx + mw, my + mh, paint)
        paint.color = neon; paint.style = Paint.Style.STROKE
        c.drawRect(mx, my, mx + mw, my + mh, paint)
        paint.style = Paint.Style.FILL
        val sx = mw / worldW
        // Camera window
        paint.color = Color.argb(60, 255, 255, 255)
        val cxw = w * sx
        c.drawRect(mx + cameraX * sx, my, mx + cameraX * sx + cxw, my + mh, paint)
        // Humans
        paint.color = yellow
        for (hm in humans) c.drawCircle(mx + hm.x * sx, my + mh - 4f, 1.5f, paint)
        // Enemies
        for (e in enemies) {
            paint.color = e.color
            c.drawCircle(mx + e.x * sx, my + mh * 0.5f, 2f, paint)
        }
        // Ship
        paint.color = cyan
        c.drawCircle(mx + shipX * sx, my + mh * 0.4f, 2.5f, paint)
    }

    private fun drawHud(c: Canvas) {
        textPaint.color = neon
        c.drawText("SCORE $score", 20f, 40f, textPaint)
        c.drawText("WAVE $wave", 20f, 76f, smallText.also { it.color = neon })
        c.drawText("LIVES $lives   BOMBS $bombs", w - 420f, 40f, textPaint)
        val hint = buildString {
            append("A fire  B bomb  Y hyper  X flip  START pause")
            if (invincible) append("  [INV]")
            if (rapidFire) append("  [RAPID]")
            if (freezeT > 0f) append("  [FREEZE ${freezeT.toInt() + 1}]")
        }
        smallText.color = Color.argb(180, 150, 200, 255)
        c.drawText(hint, 20f, h - 20f, smallText)
    }

    private fun centerText(c: Canvas, s: String, y: Float = h / 2f) {
        textPaint.color = neon
        val tw = textPaint.measureText(s)
        c.drawText(s, (w - tw) / 2f, y, textPaint)
    }

    fun screenX(worldX: Float): Float {
        var d = worldX - cameraX
        if (d > worldW / 2f) d -= worldW
        if (d < -worldW / 2f) d += worldW
        return d
    }

    // ---------- Entity classes ----------
    abstract class Enemy(var x: Float, var y: Float, val r: Float, val points: Int, val color: Int) {
        var alive = true
        abstract fun update(dt: Float, dm: Float, g: GameView)
        abstract fun draw(c: Canvas, g: GameView, sx: Float)
        open fun onDeath(g: GameView) {}
    }

    class Lander(x: Float, y: Float) : Enemy(x, y, 16f, 150, Color.rgb(0xFF, 0x66, 0x66)) {
        var carrying: Human? = null
        private var fireT = 1f + Random.nextFloat() * 2f
        override fun update(dt: Float, dm: Float, g: GameView) {
            val tx = g.shipX; val ty = g.shipY
            if (carrying == null) {
                var target: Human? = null; var best = Float.MAX_VALUE
                for (h in g.humans) if (h.state == 0) {
                    val d = abs(g.wrapDx(h.x, x)); if (d < best) { best = d; target = h }
                }
                if (target != null && best < 500f) {
                    x = g.wrap(x + sign(g.wrapDx(target.x, x)) * 70f * dm * dt)
                    y += ((g.groundY - 14f) - y) * dt * 1.2f
                    if (abs(g.wrapDx(target.x, x)) < 12f && y > g.groundY - 30f) {
                        carrying = target; target.state = 1
                    }
                } else {
                    x = g.wrap(x + sign(g.wrapDx(tx, x)) * 50f * dm * dt)
                    y += (ty - y) * dt * 0.6f
                }
                fireT -= dt
                if (fireT <= 0f && abs(g.wrapDx(tx, x)) < 600f) {
                    g.spawnEnemyBullet(x, y, tx, ty, 320f * dm); fireT = 1.5f + Random.nextFloat() * 2f
                }
            } else {
                y -= 60f * dt
                carrying!!.x = x; carrying!!.y = y + 14f
                if (y < 40f) {
                    g.humans.remove(carrying!!)
                    g.enemies.add(Mutant(x, y))
                    Sfx.play("mutate")
                    carrying = null
                    alive = false
                }
            }
        }
        override fun onDeath(g: GameView) {
            carrying?.let { it.state = 2; it.vy = 0f; carrying = null }
        }
        override fun draw(c: Canvas, g: GameView, sx: Float) {
            val p = g.paint; p.color = color
            c.drawCircle(sx, y, 10f, p)
            c.drawRect(sx - 10f, y + 4f, sx + 10f, y + 10f, p)
            if (carrying != null) {
                p.color = g.yellow
                c.drawRect(sx - 2f, y + 14f, sx + 2f, y + 24f, p)
            }
        }
    }

    class Mutant(x: Float, y: Float) : Enemy(x, y, 14f, 250, Color.rgb(0xFF, 0x22, 0xAA)) {
        private var fireT = 0.6f + Random.nextFloat()
        override fun update(dt: Float, dm: Float, g: GameView) {
            val dx = g.wrapDx(g.shipX, x); val dy = g.shipY - y
            val d = max(1f, hypot(dx, dy))
            x = g.wrap(x + dx / d * 160f * dm * dt)
            y += dy / d * 140f * dm * dt
            fireT -= dt
            if (fireT <= 0f) { g.spawnEnemyBullet(x, y, g.shipX, g.shipY, 360f * dm); fireT = 1.2f }
        }
        override fun draw(c: Canvas, g: GameView, sx: Float) {
            val p = g.paint; p.color = color
            c.drawCircle(sx, y, 9f, p)
            c.drawCircle(sx - 6f, y - 6f, 3f, p); c.drawCircle(sx + 6f, y - 6f, 3f, p)
        }
    }

    class Baiter(x: Float, y: Float) : Enemy(x, y, 14f, 200, Color.rgb(0xFF, 0xFF, 0x44)) {
        private var fireT = 0.8f
        override fun update(dt: Float, dm: Float, g: GameView) {
            x = g.wrap(x + sign(g.wrapDx(g.shipX, x)) * 220f * dm * dt)
            y += (g.shipY - y) * dt * 2.4f
            fireT -= dt
            if (fireT <= 0f) { g.spawnEnemyBullet(x, y, g.shipX, g.shipY, 420f * dm); fireT = 0.9f }
        }
        override fun draw(c: Canvas, g: GameView, sx: Float) {
            val p = g.paint; p.color = color
            val path = Path().apply {
                moveTo(sx - 14f, y); lineTo(sx, y - 6f); lineTo(sx + 14f, y)
                lineTo(sx, y + 6f); close()
            }
            c.drawPath(path, p)
        }
    }

    class Bomber(x: Float, y: Float) : Enemy(x, y, 18f, 250, Color.rgb(0x66, 0xFF, 0xAA)) {
        private var dir = if (Random.nextBoolean()) 1f else -1f
        private var mineT = 1.5f
        override fun update(dt: Float, dm: Float, g: GameView) {
            x = g.wrap(x + dir * 90f * dm * dt)
            y += sin((x + y) * 0.01f) * 20f * dt
            mineT -= dt
            if (mineT <= 0f) {
                mineT = 1.8f
                g.enemies.add(Mine(x, y + 10f))
            }
        }
        override fun draw(c: Canvas, g: GameView, sx: Float) {
            val p = g.paint; p.color = color
            val path = Path().apply {
                moveTo(sx - 18f, y); lineTo(sx, y - 10f); lineTo(sx + 18f, y)
                lineTo(sx + 10f, y + 8f); lineTo(sx - 10f, y + 8f); close()
            }
            c.drawPath(path, p)
        }
    }

    class Mine(x: Float, y: Float) : Enemy(x, y, 10f, 50, Color.rgb(0xAA, 0xAA, 0xAA)) {
        private var t = 0f
        override fun update(dt: Float, dm: Float, g: GameView) { t += dt }
        override fun draw(c: Canvas, g: GameView, sx: Float) {
            val p = g.paint; p.color = if ((t * 4).toInt() % 2 == 0) color else Color.WHITE
            c.drawCircle(sx, y, 7f, p)
        }
    }

    class Pod(x: Float, y: Float) : Enemy(x, y, 20f, 500, Color.rgb(0x88, 0x66, 0xFF)) {
        override fun update(dt: Float, dm: Float, g: GameView) {
            x = g.wrap(x + 40f * dm * dt)
            y += sin(x * 0.005f) * 15f * dt
        }
        override fun draw(c: Canvas, g: GameView, sx: Float) {
            val p = g.paint; p.color = color
            c.drawCircle(sx, y, 16f, p)
            p.color = Color.BLACK
            c.drawCircle(sx, y, 10f, p)
        }
    }

    class Swarmer(x: Float, y: Float) : Enemy(x, y, 8f, 150, Color.rgb(0xFF, 0x99, 0x33)) {
        override fun update(dt: Float, dm: Float, g: GameView) {
            val dx = g.wrapDx(g.shipX, x); val dy = g.shipY - y
            val d = max(1f, hypot(dx, dy))
            x = g.wrap(x + dx / d * 200f * dm * dt + sin((x + y) * 0.05f) * 60f * dt)
            y += dy / d * 180f * dm * dt
        }
        override fun draw(c: Canvas, g: GameView, sx: Float) {
            val p = g.paint; p.color = color
            c.drawCircle(sx, y, 6f, p)
        }
    }

    class Bullet(var x: Float, var y: Float, val vx: Float, val vy: Float,
                 var life: Float = 1.0f, val color: Int = Color.WHITE) {
        fun update(dt: Float) { x += vx * dt; y += vy * dt; life -= dt }
    }

    class Human(var x: Float) {
        var y: Float = 0f
        var vy: Float = 0f
        // 0=walking free, 1=abducted (held by lander), 2=falling, 3=carried by ship, -1=dead
        var state: Int = 0
        private var dir = if (Random.nextBoolean()) 1f else -1f
        fun update(dt: Float, g: GameView) {
            when (state) {
                0 -> {
                    y = g.groundY - 14f
                    x = g.wrap(x + dir * 20f * dt)
                    if (Random.nextFloat() < 0.005f) dir = -dir
                }
                2 -> {
                    vy += 400f * dt
                    y += vy * dt
                    // Ship catches us mid-air
                    if (abs(g.wrapDx(g.shipX, x)) < 22f && abs((g.shipY + 14f) - y) < 20f) {
                        state = 3; vy = 0f
                        g.score += 250; Sfx.play("pickup")
                    } else if (y >= g.groundY - 14f) {
                        y = g.groundY - 14f
                        if (vy > 320f) { state = -1 } // splat
                        else { state = 0; vy = 0f; Sfx.play("pickup") }
                    }
                }
                3 -> {
                    x = g.shipX; y = g.shipY + 16f
                    if (y >= g.groundY - 20f) {
                        state = 0; y = g.groundY - 14f
                        g.score += 500; Sfx.play("pickup")
                    }
                }
            }
        }
    }

    class Particle(var x: Float, var y: Float, val vx: Float, val vy: Float) {
        var life = 0.6f
        fun update(dt: Float) { x += vx * dt; y += vy * dt; life -= dt }
    }
}
