/*
 * Copyright (C) 2012 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.caliper.runner.instrument;

import com.google.caliper.core.BenchmarkClassModel;
import com.google.caliper.core.BenchmarkClassModel.MethodModel;
import com.google.caliper.core.InvalidBenchmarkException;
import com.google.caliper.core.InvalidInstrumentException;
import com.google.caliper.runner.RunScoped;
import com.google.caliper.runner.config.CaliperConfig;
import com.google.caliper.runner.config.InstrumentConfig;
import com.google.caliper.runner.config.VmType;
import com.google.caliper.runner.instrument.Instrument.InstrumentedMethod;
import com.google.caliper.runner.options.CaliperOptions;
import com.google.caliper.util.InvalidCommandException;
import com.google.caliper.util.ShortDuration;
import com.google.caliper.util.Stderr;
import com.google.caliper.util.Util;
import com.google.common.base.Function;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Ordering;
import dagger.MapKey;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoMap;
import java.io.PrintWriter;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import javax.inject.Provider;

/** Configures the {@link Instrument}s for a Caliper run. */
@Module(includes = NanoTimeGranularityModule.class)
public abstract class InstrumentModule {
  private InstrumentModule() {}

  /**
   * Specifies the {@link Class} object to use as a key in the map of available {@link Instrument
   * instruments} passed to {@link #provideInstruments},
   */
  @MapKey
  public @interface InstrumentClassKey {
    Class<? extends Instrument> value();
  }

  @Provides
  @IntoMap
  @InstrumentClassKey(ArbitraryMeasurementInstrument.class)
  static Instrument provideArbitraryMeasurementInstrument() {
    return new ArbitraryMeasurementInstrument();
  }

  @Provides
  @IntoMap
  @InstrumentClassKey(RuntimeInstrument.class)
  static Instrument provideRuntimeInstrument(
      @NanoTimeGranularity ShortDuration nanoTimeGranularity) {
    return new RuntimeInstrument(nanoTimeGranularity);
  }

  @RunScoped
  @Provides
  static ImmutableSet<Instrument> provideInstruments(
      CaliperOptions options,
      final CaliperConfig config,
      Map<Class<? extends Instrument>, Provider<Instrument>> availableInstruments,
      ImmutableSet<VmType> vmTypes,
      @Stderr PrintWriter stderr)
      throws InvalidCommandException {

    ImmutableSet.Builder<Instrument> builder = ImmutableSet.builder();
    ImmutableSet<String> configuredInstruments = config.getConfiguredInstruments();
    ImmutableSet<String> selectedInstruments = options.instrumentNames();

    if (selectedInstruments.isEmpty()) {
      selectedInstruments = config.getDefaultInstruments();
    }

    for (final String instrumentName : selectedInstruments) {
      if (!configuredInstruments.contains(instrumentName)) {
        throw new InvalidCommandException(
            "%s is not a configured instrument (%s). "
                + "use --print-config to see the configured instruments.",
            instrumentName, configuredInstruments);
      }
      final InstrumentConfig instrumentConfig = config.getInstrumentConfig(instrumentName);
      String className = instrumentConfig.className();
      try {
        Class<? extends Instrument> clazz =
            Util.lenientClassForName(className).asSubclass(Instrument.class);
        Provider<Instrument> instrumentProvider = availableInstruments.get(clazz);
        if (instrumentProvider == null) {
          throw new InvalidInstrumentException("Instrument %s not supported", className);
        }

        if (isSupportedByAllVms(clazz, vmTypes)) {
          Instrument instrument = instrumentProvider.get();
          InstrumentInjectorModule injectorModule =
              new InstrumentInjectorModule(instrumentConfig, instrumentName);
          InstrumentComponent instrumentComponent =
              DaggerInstrumentComponent.builder().instrumentInjectorModule(injectorModule).build();
          instrumentComponent.injectInstrument(instrument);
          builder.add(instrument);
        } else {
          stderr.format(
              "Instrument %s not supported on at least one target VM; ignoring\n", className);
        }
      } catch (ClassNotFoundException e) {
        throw new InvalidCommandException("Cannot find instrument class '%s'", className);
      }
    }
    return builder.build();
  }

  /**
   * If the user is running the benchmark against multiple VMs, we can only use instruments that all
   * of those VMs support.
   */
  private static boolean isSupportedByAllVms(
      Class<?> instrumentClass, ImmutableSet<VmType> vmTypes) {
    for (VmType vmType : vmTypes) {
      if (!vmType.supports(instrumentClass)) {
        return false;
      }
    }
    return true;
  }

  @Provides
  static ImmutableSet<InstrumentedMethod> provideInstrumentedMethods(
      CaliperOptions options,
      BenchmarkClassModel benchmarkClass,
      ImmutableSet<Instrument> instruments)
      throws InvalidBenchmarkException {
    ImmutableSet.Builder<InstrumentedMethod> builder = ImmutableSet.builder();
    ImmutableSet<String> benchmarkMethodNames = options.benchmarkMethodNames();
    Set<String> unusedBenchmarkNames = new HashSet<String>(benchmarkMethodNames);
    for (Instrument instrument : instruments) {
      for (MethodModel method : findAllBenchmarkMethods(benchmarkClass, instrument)) {
        if (benchmarkMethodNames.isEmpty() || benchmarkMethodNames.contains(method.name())) {
          builder.add(instrument.createInstrumentedMethod(method));
          unusedBenchmarkNames.remove(method.name());
        }
      }
    }
    if (!unusedBenchmarkNames.isEmpty()) {
      throw new InvalidBenchmarkException(
          "Invalid benchmark method(s) specified in options: %s", unusedBenchmarkNames);
    }
    return builder.build();
  }

  private static ImmutableSortedSet<MethodModel> findAllBenchmarkMethods(
      BenchmarkClassModel benchmarkClass, Instrument instrument) throws InvalidBenchmarkException {
    ImmutableSortedSet.Builder<MethodModel> result =
        ImmutableSortedSet.orderedBy(
            Ordering.natural()
                .onResultOf(
                    new Function<MethodModel, String>() {
                      @Override
                      public String apply(MethodModel method) {
                        return method.name();
                      }
                    }));
    Set<String> benchmarkMethodNames = new HashSet<String>();
    Set<String> overloadedMethodNames = new TreeSet<String>();
    for (MethodModel method : benchmarkClass.methods()) {
      if (instrument.isBenchmarkMethod(method)) {
        result.add(method);
        if (!benchmarkMethodNames.add(method.name())) {
          overloadedMethodNames.add(method.name());
        }
      }
    }
    if (!overloadedMethodNames.isEmpty()) {
      throw new InvalidBenchmarkException(
          "Overloads are disallowed for benchmark methods, found overloads of %s in benchmark %s",
          overloadedMethodNames, benchmarkClass.simpleName());
    }
    return result.build();
  }
}
