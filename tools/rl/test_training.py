"""Numerical check of the policy-gradient implementation; no Java environment needed."""
import unittest
import numpy as np
from train import Policy


class PolicyGradientTest(unittest.TestCase):
    def test_reinforce_and_entropy_gradient_matches_finite_differences(self):
        rng = np.random.default_rng(41)
        policy = Policy(5, rng, hidden=4)
        features = rng.normal(size=(7, 5))
        x, h, _, p = policy.forward(features)
        delta = -p.copy(); delta[3] += 1
        logp = np.log(p)
        gradient = policy.gradient(x, h, 1.3 * delta - .01 * p * (logp - np.dot(p, logp)))

        def objective():
            q = policy.forward(features)[3]
            return 1.3 * np.log(q[3]) - .01 * np.dot(q, np.log(q))

        for parameter, expected in zip(policy.p, gradient):
            for index in np.ndindex(parameter.shape):
                original = parameter[index]
                parameter[index] = original + 1e-6
                plus = objective()
                parameter[index] = original - 1e-6
                minus = objective()
                parameter[index] = original
                self.assertAlmostEqual(expected[index], (plus - minus) / 2e-6, places=7)


if __name__ == '__main__':
    unittest.main()
