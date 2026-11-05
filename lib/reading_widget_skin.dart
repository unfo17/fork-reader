enum ReadingWidgetSkin {
  original,
  seasons;

  static ReadingWidgetSkin fromId(String? value) =>
      values.where((e) => e.name == value).firstOrNull ?? original;
  String get label => this == original ? '简洁' : '四季';
}

int readingDailyGoal(int? value) => (value ?? 0).clamp(0, 1440);
String readingGoalCaption(int minutes, int? seconds) =>
    '每日目标 $minutes 分钟 · 已读 ${(seconds ?? 0) ~/ 60} 分钟';
