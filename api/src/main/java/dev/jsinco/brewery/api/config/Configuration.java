package dev.jsinco.brewery.api.config;

import java.util.Locale;

public interface Configuration {

  Locale language();

  Barrels barrels();

  Cauldrons cauldrons();

  double distillOverTolerance();

  interface Barrels {

    long agingYearTicks();
  }

  interface Cauldrons {

    long cookingMinuteTicks();
  }
}
