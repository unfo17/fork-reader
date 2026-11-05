import 'dart:math' as math;
import 'package:flutter/widgets.dart';

/// Lightweight perspective turn for the public-source preview. The upstream
/// curved-paper mesh is not published; retained children still avoid relayout.
class ReaderPaperClock extends ChangeNotifier {
  ReaderPaperClock(this.animation) {
    animation.addListener(notifyListeners);
  }
  final Animation<double> animation;
  double touchY = .5;
  @override
  void dispose() {
    animation.removeListener(notifyListeners);
    super.dispose();
  }
}

class ReaderSpineTurn extends StatelessWidget {
  const ReaderSpineTurn({
    super.key,
    required this.animation,
    required this.active,
    required this.incoming,
    required this.left,
    required this.fromRight,
    required this.paper,
    required this.child,
    this.single = false,
    this.gap = 0,
    this.clock,
  });
  final Animation<double> animation;
  final ReaderPaperClock? clock;
  final bool active, incoming, left, fromRight, single;
  final double gap;
  final Color paper;
  final Widget child;
  @override
  Widget build(BuildContext context) {
    if (!active) return child;
    return AnimatedBuilder(
      animation: animation,
      child: child,
      builder: (context, child) {
        final t = animation.value.clamp(0.0, 1.0);
        final progress = incoming ? 1 - t : t;
        return ClipRect(
          child: Transform(
            alignment: fromRight ? Alignment.centerLeft : Alignment.centerRight,
            transform: Matrix4.identity()
              ..setEntry(3, 2, .001)
              ..rotateY((fromRight ? -1 : 1) * progress * math.pi / 2),
            child: ColoredBox(color: paper, child: child),
          ),
        );
      },
    );
  }
}
