package ru.teplotrassa;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import ru.teplotrassa.engine.Planner;
import ru.teplotrassa.engine.Rules;

class RankingTest {
  @ParameterizedTest
  @ValueSource(strings = {"appendix", "protocol"})
  void increasingCostOrLengthReducesRating(String profile) {
    Planner.Options options = new Planner.Options();
    options.rankingProfile = profile;
    double best = options.score(25e6, 100);
    for (double worse : new double[] {options.score(50e6, 100), options.score(25e6, 200)}) {
      assertTrue(worse > best);
      assertTrue(Rules.relativeRating(worse, best) < Rules.relativeRating(best, best));
      assertTrue(Rules.relativeRating(worse, best) >= 0);
    }
  }

  @Test
  void costLengthTradeoffKeepsTheSelectedWeights() {
    Planner.Options options = new Planner.Options();
    double cheaper = options.score(25e6, 200), shorter = options.score(50e6, 100);
    assertTrue(cheaper < shorter, "70/30 favours the cheaper candidate in this tradeoff");
    assertEquals(100, Rules.relativeRating(cheaper, cheaper));
    assertTrue(Rules.relativeRating(shorter, cheaper) < 100);
    options.rankingProfile = "protocol";
    cheaper = options.score(25e6, 200);
    shorter = options.score(50e6, 100);
    assertTrue(shorter < cheaper, "30/70 favours the shorter candidate in this tradeoff");
    assertTrue(Rules.relativeRating(cheaper, shorter) < 100);
  }

  @Test
  void retiredEconomicOmissionOptionCannotEnablePartialVariants() throws Exception {
    Planner.Options options =
        new ObjectMapper().readValue("{\"allowEconomicOmission\":true}", Planner.Options.class);
    assertFalse(new ObjectMapper().valueToTree(options).has("allowEconomicOmission"));
  }

  @Test
  void tiesZeroAndLargeScoresAreFiniteAndWithinTheScale() {
    assertEquals(100, Rules.relativeRating(0, 0));
    assertEquals(0, Rules.relativeRating(2, 0));
    assertEquals(100, Rules.relativeRating(18.4, 18.4));
    assertEquals(100, Rules.relativeRating(Double.MAX_VALUE, Double.MAX_VALUE));
    assertEquals(50, Rules.relativeRating(Double.MAX_VALUE, Double.MAX_VALUE / 2));
  }

  @Test
  void invalidIndicesAreNotPresentedAsRatings() {
    for (double bad : new double[] {-1, Double.NaN, Double.POSITIVE_INFINITY}) {
      assertThrows(IllegalArgumentException.class, () -> Rules.relativeRating(bad, 0));
      assertThrows(IllegalArgumentException.class, () -> Rules.relativeRating(1, bad));
    }
    assertThrows(IllegalArgumentException.class, () -> Rules.relativeRating(1, 2));
  }

  @Test
  void historicalReportKeepsItsOriginalCostAndRating() throws Exception {
    JsonNode variants =
        new ObjectMapper()
            .readTree(Path.of("../validation/supplied-ui-grid20-report.json").toFile())
            .path("variants");
    double[] expectedRatings = {100, 65.99377032834853, 43.71057347951218};
    assertEquals(3, variants.size());
    double best = variants.get(0).path("score").asDouble();
    double previousScore = -1, previousRating = 101;
    for (int i = 0; i < variants.size(); i++) {
      JsonNode variant = variants.get(i);
      double cost = 0;
      for (String field :
          new String[] {
            "construction_cost", "chamber_construction_cost", "tie_in_cost",
            "reconstruction_cost", "chamber_reconstruction_cost", "unconnected_penalty"
          }) cost += variant.path(field).asDouble();
      assertEquals(variant.path("calculated_cost").asDouble(), cost, 1e-5);
      double length =
          variant.path("new_network_length").asDouble()
              + variant.path("reconstruction_length").asDouble();
      assertEquals(variant.path("length").asDouble(), length, 1e-6);
      double score = Rules.score(cost, length);
      assertEquals(variant.path("score").asDouble(), score, 1e-10);
      double rating = Rules.relativeRating(score, best);
      assertEquals(expectedRatings[i], rating, 1e-10);
      assertTrue(score > previousScore);
      assertTrue(rating < previousRating);
      assertEquals(i + 1, variant.path("rank").asInt());
      previousScore = score;
      previousRating = rating;
    }
  }
}
