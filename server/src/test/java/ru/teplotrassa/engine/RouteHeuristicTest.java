package ru.teplotrassa.engine;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class RouteHeuristicTest {
  @Test
  void estimateIncludesOnlyUnavoidableGridTurnsAndDoesNotChargeGoalTwice() {
    assertEquals(70, RouteFinder.remainingCost(10, 10, 0, 0, 0d), 1e-8);
    assertEquals(45, RouteFinder.remainingCost(10, 10, 1, 0, 0d), 1e-8);
    assertEquals(10, RouteFinder.remainingCost(10, 0, 0, 0, 0d), 1e-8);
    assertEquals(0, RouteFinder.remainingCost(0, 0, 1, 0, 0d), 1e-8);
  }

  @Test
  void potentialIsConsistentForEveryGridMoveAndReceivingAxisMode() {
    int[] x = {1, 0, -1, 0}, y = {0, 1, 0, -1};
    for (double receiving : new double[] {0, .371, 1.138})
      for (double rotation : new double[] {0, Math.PI / 4})
        for (boolean free : new boolean[] {false, true}) {
          Double axis = free ? null : receiving;
          double theta = receiving + rotation;
          for (double dx : new double[] {-10, -1, 0, 1, 10})
            for (double dy : new double[] {-10, -1, 0, 1, 10})
              for (int heading = 0; heading < 4; heading++)
                for (int direction = 0; direction < 4; direction++) {
                  if (direction == (heading + 2) % 4) continue;
                  double nx = dx - x[direction], ny = dy - y[direction];
                  double edge = 1 + (direction == heading ? 0 : RoutingQuality.TURN_90_M);
                  if (nx == 0 && ny == 0)
                    edge += RoutingQuality.connectionPenalty(theta + direction * Math.PI / 2, axis);
                  double before = RouteFinder.remainingCost(dx, dy, heading, theta, axis);
                  double after = RouteFinder.remainingCost(nx, ny, direction, theta, axis);
                  assertTrue(
                      before <= edge + after + 1e-8,
                      "An overestimating/inconsistent heuristic could hide a cheaper route");
                }
        }
  }
}
