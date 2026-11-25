enum ReadingWidgetSkin {
  original,
  seasons,
  archive,
  night,
  film,
  paper;

  static ReadingWidgetSkin fromId(String? value) =>
      values.where((e) => e.name == value).firstOrNull ?? original;
  String get label => switch (this) {
    original => '简洁',
    seasons => '四季手帐',
    archive => '读者档案',
    night => '夜读之城',
    film => '胶片三幕',
    paper => '剪纸',
  };
}

int readingDailyGoal(int? value) => (value ?? 0).clamp(0, 1440);
String readingGoalCaption(int minutes, int? seconds) =>
    '每日目标 $minutes 分钟 · 已读 ${(seconds ?? 0) ~/ 60} 分钟';
