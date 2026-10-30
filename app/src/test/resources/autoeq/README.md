# AutoEq test fixtures

Used by the JVM tests only; nothing here goes into the app. All from AutoEq
(github.com/jaakkopasanen/AutoEq, MIT licence, Jaakko Pasanen) at commit
7ae0f56d53074872b028649617a22bbb4232feb7.

- `oratory1990 Sennheiser HD 650.csv`, `Rtings HMS II.3 Samsung Galaxy Buds2.csv`,
  `Rtings 5128 Sony WH-1000XM4.csv`: the frequency, raw, target and equalization columns of
  those published results (results/<source>/<rig form>/<name>/<name>.csv). The measurements
  are third-party data with no stated licence; the app fetches them at runtime instead.
- `target … without bass.csv`: AutoEq target files (targets/).
- `… expected.csv`: what AutoEq's own code (process() with min_mean_error, no bass boost, then
  equalize(); run with numpy/scipy) gives for each result against its rig's target above.
- `synthetic.csv`: a made-up error curve that exercises every branch of equalize(), with
  scipy's savgol_filter outputs and AutoEq's smoothed error and equalization for it.
