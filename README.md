# HistoForge for QuPath

A QuPath extension for tissue analysis and model fine-tuning.

- Segment and classify tissue structures.
- Calculate measurements and export results.
- Prepare annotated training data and fine-tune models.

Analysis modules define the models, classes, and measurements. Inference and
training run in your own Modal workspace; QuPath displays and saves the results.

Supports QuPath 0.6.0 and 0.7.0.

## Setup

See the [HistoForge documentation](https://histoforge.github.io/docs/) for installation
and usage.

## Development

Build with JDK 21 for QuPath 0.6.0:

```bash
./gradlew build shadowJar
```

See [Testing](TESTING.md) for QuPath 0.7.0, Windows commands, and test coverage.

## License

The extension is licensed under [GPL-3.0](LICENSE). Models and datasets have
their own licenses and access requirements.
