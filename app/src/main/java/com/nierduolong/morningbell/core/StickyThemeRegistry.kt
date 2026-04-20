package com.nierduolong.morningbell.core

import kotlin.random.Random

/**
 * 便利贴「语录」分支的主题包：每包含展示名 + 语录池。
 * [quoteCategory] 固定为「语录」类；[cardTheme] 为主题包人类可读名；[tagline] 为风格一句话；[id] 即 userSelectedThemePack。
 */
data class StickyThemePack(
    val id: String,
    /** 卡片主风格名，如「大学生生存系」 */
    val cardTheme: String,
    /** 内容大类，用于与目标/天气区分 */
    val quoteCategory: String,
    /** 一句话风格定位（首页 / 便利贴卡片展示） */
    val tagline: String,
    val quotes: List<String>,
) {
    fun randomQuote(): String =
        if (quotes.isEmpty()) {
            "今天也请温柔地对待自己。"
        } else {
            quotes[Random.nextInt(quotes.size)]
        }
}

object StickyThemeRegistry {
    const val DEFAULT_PACK_ID = "healing"

    private val packs: List<StickyThemePack> =
        listOf(
            StickyThemePack(
                id = "healing",
                cardTheme = "治愈系",
                quoteCategory = "语录",
                tagline = "温柔打气，小声加油",
                quotes =
                    listOf(
                        "慢一点也没关系，你还在路上。",
                        "今天的阳光会落在你的桌子上。",
                        "你已经比昨天更靠近想要的生活了。",
                        "辛苦了，你已经做得很好。",
                        "允许自己偶尔断电，也是在充电。",
                        "把注意力放回呼吸上，一切都会过去。",
                        "愿你有不被打扰的清静，也有有人惦记的温暖。",
                        "不必今天就把所有问题解决，先把自己安顿好。",
                        "小步也是前进，你已经在动了。",
                        "世界很大，你值得被温柔地接住。",
                    ),
            ),
            StickyThemePack(
                id = "cold_humor",
                cardTheme = "冷幽默系",
                quoteCategory = "语录",
                tagline = "冷笑话叫醒服务",
                quotes =
                    listOf(
                        "闹钟响了，宇宙在问：你还打算假装听不见多久？",
                        "床很爱你，但DDL不一定等你。",
                        "你不是起不来，你只是和被子谈判失败。",
                        "今天的你，已经比昨天的闹钟多活了一秒，恭喜。",
                        "再赖五分钟，世界不会毁灭——但你的早饭可能会。",
                        "科学家说：适度起床有助于不像树懒。",
                        "眼睛睁不开没关系，先把灵魂寄存在咖啡里。",
                        "起床失败？没关系，地球还在转，你也还有机会。",
                        "温馨提示：被子不会替你交作业。",
                        "你不是懒，你是在进行「静止节能」实验。",
                    ),
            ),
            StickyThemePack(
                id = "college_survival",
                cardTheme = "大学生生存系",
                quoteCategory = "语录",
                tagline = "先活下来，再谈理想",
                quotes =
                    listOf(
                        "今天先别思考人生，先去洗脸。",
                        "DDL 还没到，你还有呼吸空间。",
                        "食堂开门了，这就是起床的物理学理由。",
                        "教室后排不会自己长出来，但你可以走过去。",
                        "签到码不会等你刷完牙再出现。",
                        "图书馆空位是稀缺资源，早起的鸟有插座。",
                        "小组作业里，第一个醒的人拥有命名权。",
                        "今天没课？那正好把昨天没睡够的觉睡成质量款。",
                        "快递到了，这是宇宙发的起床红包。",
                        "论文不会自己写完，但你可以先写一个字。",
                        "绩点看不见你，但成绩单会。温柔提醒一下。",
                    ),
            ),
            StickyThemePack(
                id = "exam_sprint",
                cardTheme = "考试冲刺系",
                quoteCategory = "语录",
                tagline = "书在桌上，人在路上",
                quotes =
                    listOf(
                        "你不是起不来，你只是还没想起自己还有考试。",
                        "翻开书的第一页，焦虑就会少一厘米。",
                        "今天背一个点，考场上就少一次蒙。",
                        "复习像存钱：每天一点点，到期有利息。",
                        "错题本在等你，它比前任专一。",
                        "模考分数会波动，但你的努力曲线在往上。",
                        "先坐进自习室，大脑会自动进入战斗模式。",
                        "倒计时不是恐吓，是提醒你还有机会。",
                        "今晚早睡一小时，明天多记一页纸。",
                        "你不是一个人在冲刺，还有咖啡和台灯。",
                    ),
            ),
            StickyThemePack(
                id = "love_sober",
                cardTheme = "恋爱清醒系",
                quoteCategory = "语录",
                tagline = "心动可以，别弄丢自己",
                quotes =
                    listOf(
                        "喜欢可以很甜，但别弄丢自己的课表。",
                        "不回消息的人，也许在忙，也许在选择——你也有选择。",
                        "先把自己安顿好，爱才会有地方落脚。",
                        "心动正常，但别用熬夜换已读不回。",
                        "对的人不会让你长期猜「我是不是不够好」。",
                        "独处不是惩罚，是给自己充电的插座。",
                        "温柔不是无限退让，是清楚自己的底线。",
                        "今天先爱自己一分钟，再打开聊天框。",
                        "你可以主动，也可以体面地停。",
                        "爱情加分项，不是生活的全部学分。",
                    ),
            ),
            StickyThemePack(
                id = "worker",
                cardTheme = "打工人版",
                quoteCategory = "语录",
                tagline = "带薪清醒，认真糊弄",
                quotes =
                    listOf(
                        "工位在等你，但咖啡可以先到。",
                        "今天也要假装情绪稳定地打开邮箱。",
                        "会议可以拖，但打卡时间很诚实。",
                        "带薪发呆是技术活，先完成起床这一单。",
                        "周报不会写？先写「本周成功起床」。",
                        "地铁不会等你，但下一班会——所以也别太慌。",
                        "老板看不见你赖床，但全勤奖看得见。",
                        "先把人送到公司，灵魂可以晚点到。",
                        "今日 KPI：活着到公司。",
                        "打工人的浪漫：闹钟响第二遍时还没迟到。",
                    ),
            ),
            StickyThemePack(
                id = "sane_chaos",
                cardTheme = "清醒发疯版",
                quoteCategory = "语录",
                tagline = "轻微发疯，准时到站",
                quotes =
                    listOf(
                        "起床，是对被子霸权的一次温和政变。",
                        "你不是困，你是身体在抗议「昨晚又修仙」。",
                        "世界是个草台班子，但你至少要把妆画好再上台。",
                        "理智：再睡五分钟。现实：再睡五十分钟。",
                        "今天的精神状态：介于哲学家和微波炉之间。",
                        "先起床，再决定要不要恨这个世界。",
                        "生活给你柠檬，你至少先下床去接。",
                        "闹钟是外包的良心，请签收。",
                        "你可以轻微发疯，但别缺席第一节课。",
                        "清醒一点：被窝不会给你发学位证。",
                    ),
            ),
        )

    fun allPacks(): List<StickyThemePack> = packs

    fun packOrDefault(id: String): StickyThemePack =
        packs.find { it.id == id } ?: packs.first()

    fun isValidPackId(id: String): Boolean = packs.any { it.id == id }
}
