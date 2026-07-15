# Contributing to cloud-itonami-isic-5520

Contributions should preserve the actor's scope: back-office campground/RV-park
coordination only, with CRITICAL exclusions of directly issuing/executing
evacuation orders, directly contacting emergency medical services or law
enforcement, and overriding guest-safety-authority decisions (see README.md).

- All code must be .cljc (portable Clojure, no JVM-only constructs).
- Tests must pass: clojure -M:test
- Commit messages should link to relevant ADRs or issues.

**This actor does NOT:**
- Directly issue or execute an evacuation order.
- Directly contact emergency medical services or law enforcement.
- Override a guest-safety-authority decision.

Contributions that cross these boundaries will be rejected.
