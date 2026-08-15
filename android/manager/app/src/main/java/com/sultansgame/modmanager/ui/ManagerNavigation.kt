package com.sultansgame.modmanager.ui

internal enum class Destination(val title: String, val caption: String) {
    Start("开始", "准备游戏"),
    Acquire("创意工坊", "浏览与添加"),
    Library("管理Mod", "同步mod列表"),
    Settings("设置", "帮助与存储"),
}

internal fun visibleDestinations(showWorkshop: Boolean): List<Destination> = buildList {
    add(Destination.Start)
    add(Destination.Library)
    if (showWorkshop) add(Destination.Acquire)
    add(Destination.Settings)
}

internal fun effectiveDestination(
    selected: Destination,
    showWorkshop: Boolean,
): Destination = selected.takeIf { it in visibleDestinations(showWorkshop) }
    ?: Destination.Library.takeIf { it in visibleDestinations(showWorkshop) }
    ?: Destination.Start

internal fun destinationFromRoute(route: String?): Destination = route
    ?.let { value -> runCatching { Destination.valueOf(value) }.getOrNull() }
    ?: Destination.Start
