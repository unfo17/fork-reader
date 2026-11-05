import 'package:flutter/material.dart';
import 'library_state.dart';
import 'catalog.dart';
import 'reading_report_range.dart';
import 'reading_statistics_data.dart';

class ReadingReportPage extends StatelessWidget {
  const ReadingReportPage({
    super.key,
    required this.state,
    required this.repository,
    this.now,
    this.initialRange = const ReadingReportRange(),
  });
  final LibraryState state;
  final NovelRepository repository;
  final DateTime? now;
  final ReadingReportRange initialRange;
  @override
  Widget build(BuildContext context) {
    final date = now ?? DateTime.now();
    final data = ReadingStatisticsData(
      state: state,
      repository: repository,
      now: initialRange.endAt(date),
      period: initialRange.daysAt(date),
    );
    return Scaffold(
      appBar: AppBar(title: const Text('阅读报告')),
      body: ListView(
        padding: const EdgeInsets.all(24),
        children: [
          Text(
            '最近 ${data.period} 天',
            style: Theme.of(context).textTheme.headlineSmall,
          ),
          const SizedBox(height: 20),
          Text(
            '阅读 ${data.periodSeconds ~/ 60} 分钟\n活跃 ${data.activeDays} 天\n读过 ${data.periodBooks} 本书\n连续阅读 ${data.currentStreak} 天',
            style: const TextStyle(fontSize: 20, height: 2),
          ),
          const SizedBox(height: 24),
          const Text('预览版提供基础统计；上游报告插画与图片导出源码尚未公开。'),
        ],
      ),
    );
  }
}
