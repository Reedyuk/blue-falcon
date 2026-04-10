# Contributing to Blue-Falcon

:+1::tada: First off, thanks for taking the time to contribute! :tada::+1:

The following is a set of guidelines for contributing to Blue-Falcon, which are hosted in the [Blue Falcon Repo](https://github.com/reedyuk/blue-falcon/) on GitHub. These are mostly guidelines, not rules. Use your best judgment, and feel free to propose changes to this document in a pull request.

#### Table Of Contents

[Code of Conduct](#code-of-conduct)

[I don't want to read this whole thing, I just have a question!!!](#i-dont-want-to-read-this-whole-thing-i-just-have-a-question)

[How Can I Contribute?](#how-can-i-contribute)
  * [Reporting Bugs](#reporting-bugs)
  * [Suggesting Enhancements](#suggesting-enhancements)

## Code of Conduct

This project and everyone participating in it is governed by the [Blue Falcon Code of Conduct](CODE_OF_CONDUCT.md). By participating, you are expected to uphold this code. Please report unacceptable behavior to [andrew_reed@hotmail.com](mailto:andrew_reed@hotmail.com).

## I don't want to read this whole thing I just have a question!!!

Raise a github issue if you have a question, [file a Github issue](https://github.com/Reedyuk/blue-falcon/issues/new).

If there is alot of questions then i will consider setting up a slack chat.

## How Can I Contribute?

### Proposing Major Changes

For significant architectural changes, new platform support, or API modifications, we use Architecture Decision Records (ADRs):

#### 1. Create an ADR First

Before writing code, create an ADR to document your proposed change:

**Using AI (Recommended):**
```
# Use GitHub Copilot, Cursor, or your preferred AI assistant:
"Create a new ADR for adding Linux desktop support"
"Create ADR for migrating from delegates to Kotlin Flow"
```

**Manually:**
```bash
# Find the next ADR number
ls docs/adr/ | grep -E '^[0-9]{4}' | sort | tail -1

# Copy the template
cp docs/adr/ADR-TEMPLATE.md docs/adr/NNNN-your-title.md

# Fill in:
# - Context: What problem are you solving?
# - Decision: What approach will you take?
# - Consequences: What are the tradeoffs?
# - Alternatives: What else did you consider?
```

#### 2. Submit ADR for Review

Open a pull request with just the ADR. This allows discussion before implementation begins.

#### 3. Implement with AI Assistance

Once the ADR is approved, use AI to implement:

```
# Example prompts:
"Implement the changes described in ADR 0003"
"Add macOS support following the pattern in ADR 0001"
"Refactor the delegate system according to ADR 0005"
```

Our repository includes AI instructions (`.github/copilot-instructions.md`) to guide assistants through our codebase conventions, architecture, and build process.

#### When to Create an ADR

✅ **Do create an ADR for:**
- Adding new platform support (Windows, Linux, etc.)
- Changing public APIs
- Adopting new architectural patterns
- Making technology choices (dependencies, frameworks)
- Changes affecting multiple platforms

❌ **Don't create an ADR for:**
- Bug fixes
- Documentation updates
- Minor refactoring
- Performance optimizations
- Test improvements

See existing ADRs in [`/docs/adr/`](docs/adr/) for examples.

### Reporting Bugs

This section guides you through submitting a bug report for Blue Falcon. Following these guidelines helps maintainers and the community understand your report :pencil:, reproduce the behavior :computer: :computer:, and find related reports :mag_right:.

Before creating bug reports, please check [this list](#before-submitting-a-bug-report) as you might find out that you don't need to create one. When you are creating a bug report, please [include as many details as possible](#how-do-i-submit-a-good-bug-report). 

> **Note:** If you find a **Closed** issue that seems like it is the same thing that you're experiencing, open a new issue and include a link to the original issue in the body of your new one.

### Suggesting Enhancements

This section guides you through submitting an enhancement suggestion for Blue Falcon, including completely new features and minor improvements to existing functionality. Following these guidelines helps maintainers and the community understand your suggestion :pencil: and find related suggestions :mag_right:.

Before creating enhancement suggestions, please check [this list](#before-submitting-an-enhancement-suggestion) as you might find out that you don't need to create one. When you are creating an enhancement suggestion, please [include as many details as possible](#how-do-i-submit-a-good-enhancement-suggestion).
