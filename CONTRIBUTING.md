# Contributing

When contributing to this repository, please first discuss the change you wish to make via an issue with the owner of this repository before making a change.

Please note we have a [code of conduct](CODE_OF_CONDUCT.md), please follow it in all your interactions with the project.

## Pull Request Process
If you want to help this project and don't know where to start, check out the currently open issues
before creating any PRs. Because there are people that could use your help.

* Ensure you didn't use tabs! Please use spaces for indentation.
* Respect the code style. Keep Java class headers and creation dates consistent with existing files.
* Do not increase the version numbers in any examples files and the README.md to the
  new version that this Pull Request would represent.
* Create minimal diffs - disable on save actions like reformat source code or organize imports.
  If you feel the source code should be reformatted create a separate PR for this change.

## Issue Process
When you submit an issue, there will be shown a template for that. Please do not discard except if you
aren't reporting a bug, but rather suggesting code improvements, pointing out spelling mistakes etc,
you can discard the template.

* Ensure your issue is not a duplicate of any other.
* Please keep the issues section for actual bugs or code problems, not for support.
* Make sure you're using the latest version, chances are that the problem has already been solved.
* Don't post your issue in other issues, please keep everything nicely organized and make a separate one.

## Dispatch checks

* Run `pnpm --dir frontend test` and `pnpm --dir frontend build` for frontend changes.
* Run `./gradlew test bootJar` (or `gradlew.bat` on Windows) for Java changes.
* Add a new Flyway migration for database changes; do not edit applied migrations.
* Never include `.env`, `flyway.conf`, `secrets/`, `data/`, credentials, mail content, or attachments.
* Report suspected vulnerabilities privately as described in [SECURITY.md](SECURITY.md).

## Additional Resources

* [General GitHub documentation](https://help.github.com/)
* [GitHub pull request documentation](https://help.github.com/articles/creating-a-pull-request/)
