package com.nierduolong.morningbell.core

import com.nierduolong.morningbell.data.db.MoodEntity
import com.nierduolong.morningbell.data.db.WakeDayEntity
import java.time.LocalDate
import kotlin.random.Random

/**
 * 轻量「陪伴」状态：从既有起床 / 心情数据推导，无独立宠物表。
 * 非评价式：久未起床只走「困困安慰」，不扣分、不责备。
 */
object CompanionLogic {
    private const val SLEEPY_AFTER_DAYS_NO_WAKE = 3

    /** 5 档成长，仅影响形象大小与一句文案池 */
    private val stageEmojis = listOf("🌱", "🌿", "🪴", "🌳", "🌸")

    private val welcomeLines =
        listOf(
            "你好呀，我会安静待在这里。",
            "不用着急，我们一起等第一次起床记录。",
            "我不打分，只陪你慢慢找到节奏。",
        )

    private val growthLines =
        listOf(
            listOf(
                "今天的一小步，也值得被看见。",
                "有记录就很好，我们慢慢来。",
                "没谁规定你必须「元气满满」才配开始一天。",
                "我在这里，话不多，只听你今天的节奏。",
            ),
            listOf(
                "连续几天见面啦，我有在长高一点点。",
                "节律正在成形，像小芽找光。",
                "你来了，我就精神一点。",
                "不用打卡给我看，我知道你在试。",
                "这种小小的稳定，已经很珍贵。",
            ),
            listOf(
                "这样稳稳地，就很好。",
                "我在长大，你也在养自己的早晨。",
                "我们不比赛，只一起呼吸。",
                "你做的不是「自律表演」，是照顾自己。",
            ),
            listOf(
                "你已经把「起床」练成一种温柔的习惯啦。",
                "这种坚持不用发朋友圈，我也知道。",
                "像小树习惯风，你也习惯了自己的晨光。",
                "我不夸你「卷」，我只觉得你对自己很诚实。",
            ),
            listOf(
                "像小花一样，开给自己看也很好。",
                "今天的你，和晨光一样可靠。",
                "能长期温柔对待早晨的人，也值得被温柔对待。",
                "到这里就够好了，再多都是锦上添花。",
            ),
        )

    private val sleepyLines =
        listOf(
            "我也在犯困……没关系，慢慢来。",
            "几天没见起床记录啦，我不追问，只想说你还在就很好。",
            "床很暖和的话，多赖一会儿也可以，我不计时。",
            "今天起不来也没关系，明天太阳还会照常上班。",
            "我不是闹钟，不会催你，只会打个小哈欠陪你。",
            "节律断了就接上，像续一杯温水那样简单。",
            "你不需要完美起床，只需要对自己温柔一点。",
            "我在这里打盹等你，不着急。",
            "失败这个词太重了，我们只是还在找合适的早晨。",
            "世界很吵，我小声说一句：你已经够努力了。",
            "要是最近很难，就把目标缩小到「先坐起来」那么大。",
            "我不统计你赖了几天床，我只记得你总会再试一次。",
        )

    data class Presentation(
        val wakeStreak: Int,
        /** 0–4，对应 5 个陪伴档位 */
        val stageIndex: Int,
        /** 0–3，心情打卡越稳定可换越「满」的背景 */
        val moodBackgroundTier: Int,
        val isSleepyReassurance: Boolean,
        val primaryEmoji: String,
        val mainLine: String,
        val subLine: String?,
    )

    /** 从起床 / 心情列表推导今日展示（seed 用当日 epoch，同一天文案稳定） */
    fun computeToday(
        todayEpochDay: Long,
        wakes: List<WakeDayEntity>,
        moods: List<MoodEntity>,
    ): Presentation {
        val wakeDays = wakes.map { it.dayEpoch }.toSet()
        val streak = computeWakeStreak(wakeDays, todayEpochDay)
        val daysSinceLast = daysSinceLastWake(wakeDays, todayEpochDay)
        val moodTier = moodBackgroundTier(moods, todayEpochDay)
        val seed = todayEpochDay xor (moodTier * 13L)

        if (wakeDays.isEmpty()) {
            val line = pick(welcomeLines, seed)
            return Presentation(
                wakeStreak = 0,
                stageIndex = 0,
                moodBackgroundTier = moodTier,
                isSleepyReassurance = false,
                primaryEmoji = stageEmojis[0],
                mainLine = line,
                subLine = null,
            )
        }

        val sleepy =
            daysSinceLast != null &&
                daysSinceLast >= SLEEPY_AFTER_DAYS_NO_WAKE

        if (sleepy) {
            val line = pick(sleepyLines, seed)
            return Presentation(
                wakeStreak = streak,
                stageIndex = stageFromStreak(streak).coerceAtMost(stageEmojis.lastIndex),
                moodBackgroundTier = moodTier,
                isSleepyReassurance = true,
                primaryEmoji = "🌙",
                mainLine = line,
                subLine = "小芽还在，只是陪你一起打盹。",
            )
        }

        val stage = stageFromStreak(streak)
        val pool = growthLines.getOrElse(stage) { growthLines.last() }
        val line = pick(pool, seed xor streak.toLong())
        val emoji = stageEmojis.getOrElse(stage) { stageEmojis.last() }
        val sub =
            if (streak > 0) {
                "已连续 $streak 天有起床记录"
            } else {
                null
            }
        return Presentation(
            wakeStreak = streak,
            stageIndex = stage,
            moodBackgroundTier = moodTier,
            isSleepyReassurance = false,
            primaryEmoji = emoji,
            mainLine = line,
            subLine = sub,
        )
    }

    /** 连续有「起床记录」的日历天数（今天无记录则从昨天起算） */
    fun computeWakeStreak(
        wakeEpochDays: Set<Long>,
        todayEpochDay: Long,
    ): Int {
        var d = todayEpochDay
        if (!wakeEpochDays.contains(d)) {
            d -= 1
        }
        if (!wakeEpochDays.contains(d)) {
            return 0
        }
        var c = 0
        while (wakeEpochDays.contains(d)) {
            c++
            d--
        }
        return c
    }

    fun daysSinceLastWake(
        wakeEpochDays: Set<Long>,
        todayEpochDay: Long,
    ): Int? {
        if (wakeEpochDays.isEmpty()) return null
        val last = wakeEpochDays.maxOrNull() ?: return null
        return (todayEpochDay - last).toInt()
    }

    /** 近 14 个日历日内心情打卡天数 → 背景档位 */
    fun moodBackgroundTier(
        moods: List<MoodEntity>,
        todayEpochDay: Long,
    ): Int {
        val from = todayEpochDay - 13
        val distinctDays =
            moods
                .filter { it.dayEpoch in from..todayEpochDay }
                .map { it.dayEpoch }
                .distinct()
                .size
        return when {
            distinctDays >= 12 -> 3
            distinctDays >= 8 -> 2
            distinctDays >= 4 -> 1
            else -> 0
        }
    }

    fun stageFromStreak(streak: Int): Int =
        when {
            streak <= 0 -> 0
            streak <= 2 -> 1
            streak <= 4 -> 2
            streak <= 7 -> 3
            else -> 4
        }

    private fun pick(
        lines: List<String>,
        seed: Long,
    ): String {
        if (lines.isEmpty()) return ""
        val idx = Random(seed).nextInt(lines.size)
        return lines[idx]
    }
}
