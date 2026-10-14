**This is a good choice of project for that application. The presentation already makes a credible case that you can direct serious AI-assisted development. It makes a less convincing case—so far—that you should own product design as well as engineering.**

The main imbalance is that **the development process is much more visible than the product experience or your personal judgment**. I would address that before adding features or doing more branding work.

I reviewed the current README, including the banner, `AGENTS.md`, the CI and release workflows, release documentation, and a recent pull request. I did not install the APK or run the tests; this is an assessment of what the public presentation demonstrates.

| Intended signal | My assessment of the current presentation |
|---|---|
| Effective use of AI | Strong. There is a specific working method, not merely a claim that AI was involved. |
| Senior engineering judgment | Promising and supported by concrete artifacts, but some of the best evidence is buried or not publicly inspectable. |
| Product design ownership | There are good decisions here, but the reader has to infer too much. |
| Fit with Mindbloom | Strong potential, especially around AI-assisted delivery and thoughtful experiential software—not as proof of a clinical product. |

For context, the public Software Engineer listing I found is archived, so I am not assuming it is your exact opening. Nevertheless, it specifically emphasizes agentic development, testing and automation, deployment infrastructure, cross-functional collaboration, and even a psychedelic-art experience. This is a substantially more relevant sample for that brief than a generic CRUD application would be. [Quiet Job Board](https://careers.quiet.com/companies/mindbloom/jobs/60739505-software-engineer)

## What is already working

### There is actual engineering substance behind the AI story

Your release workflow is particularly useful evidence. It separates signing, verification, and publication into different jobs, limits permissions, verifies the resulting APK, and includes an emulator smoke test. The regular CI workflow includes application unit tests, native host tests, and tests for release tooling. Those are concrete artifacts a technical reviewer can inspect. [GitHub](https://raw.githubusercontent.com/carterchadams/koggles/main/.github/workflows/release.yml)

My reaction to that is not “someone prompted an app into existence.” It is “someone is thinking about how software gets built, checked, and delivered.”

The same applies to the distinction in `AGENTS.md` between emulator checks and physical-device checks, and to its insistence that a test should demonstrably detect the problem it claims to guard against. That is a much stronger signal than simply announcing a large test count. [GitHub](https://raw.githubusercontent.com/carterchadams/koggles/main/AGENTS.md)

### The product and architecture decisions are better than the presentation makes them look

Using an unmodified upstream rendering library, adopting Cardboard’s established viewer support, and offering different audio-capture paths are substantive choices already explained in the README. [GitHub](https://raw.githubusercontent.com/carterchadams/koggles/main/README.md)

These can demonstrate several desirable qualities: resisting unnecessary custom infrastructure, learning from a prototype, understanding maintenance costs, and balancing technical capability against user friction.

**You do not need to invent a more impressive project narrative. You need to bring the existing decisions into focus.**

### The honesty is an asset

Mindbloom’s careers material explicitly emphasizes intellectual honesty, clear writing, rigor, and client-centered decisions. Your willingness to distinguish a claimed result from a demonstrated result fits that unusually well. I would preserve that character rather than polish the project into an implausibly flawless success story. [Mindbloom](https://www.mindbloom.com/careers)

The issue is selection and emphasis, not excessive honesty itself.

## The largest presentation gap: I cannot readily evaluate the actual product

The README has an illustrated banner but no embedded product screenshots or demonstration video. [GitHub](https://raw.githubusercontent.com/carterchadams/koggles/main/README.md)

That is the biggest weakness for the **product design** half of your positioning.

The banner is memorable, makes the intended experience immediately legible, and gives the project a recognizable identity. The green download button is easy to distinguish from the surrounding artwork. I would keep it. But it communicates the promise of the product, not the quality of the interface you designed. [GitHub](https://raw.githubusercontent.com/carterchadams/koggles/main/images/KogglesREADMEBanner.png)

As a reviewer, I still need to see:

- **The actual experience:** a brief screen recording showing visuals, opening the menu, changing something meaningful, and entering goggles mode.
- **The actual interface:** two or three carefully selected screenshots, especially the menu and a configuration or permission flow.
- **A design decision in action:** for example, a short demonstration of headset calibration, with a sentence explaining the constraint it addresses.

A 45–60-second demonstration would do more for this application than another large block of development-process prose. It should remain understandable without sound, and it should show actual captured output rather than an illustrative animation.

I would also make **“Watch the demo” available alongside “Download for Android.”** Design the review path so that nobody needs an Android device, permission to sideload software, or a headset merely to understand what you accomplished.

That is itself an opportunity to demonstrate product thinking: the reviewer is another user whose constraints you can accommodate.

## Your personal contribution needs clearer ownership

The README describes the agents as building the application, with you directing. [GitHub](https://raw.githubusercontent.com/carterchadams/koggles/main/README.md) That is candid, but **“directing” is doing too much work**.

It could mean any of the following: defining the product, choosing the architecture, writing acceptance criteria, approving proposed changes, testing the experience, or simply answering occasional agent questions. Those are very different levels of contribution.

For this application, I would add a short **“My role”** paragraph that makes the actual division of responsibility explicit. Something along these lines, limited to what is accurate:

> I owned the product direction, architectural tradeoffs, and acceptance criteria. Claude Code handled implementation, with Codex providing adversarial review. I evaluated the experience on physical hardware, challenged unsupported conclusions, and decided which behavior was acceptable to ship.

That does not hide AI involvement. It tells the reader what they would be hiring **you** to do.

I would also replace the repeated third-person “the author” with first person in the case-study material. The repository can be a public software project without making its creator sound like an unidentified person in an academic paper.

### Give product decisions the same specificity as engineering decisions

At present, the README tells me a fair amount about *what controls exist*. I would rather see a little more about *why the experience is organized that way*.

The volume-button lens calibration is a particularly good candidate: it is an interaction shaped by the physical circumstances of using a phone in a headset. [GitHub](https://raw.githubusercontent.com/carterchadams/koggles/main/README.md)

A small case study could explain the practical problem, the alternatives considered, why you chose the implemented interaction, and what you actually observed when testing it. The important distinction is between:

> “The application supports lens-spacing configuration.”

and:

> “I designed the adjustment flow around the fact that the touchscreen is difficult to access once the phone is mounted.”

The second demonstrates product judgment. A short video showing that adjustment would make it concrete.

Do the same for the audio experience: explain the **user’s interruption and permission burden**, not primarily the underlying API difference. The technical detail can remain available immediately below.

Also distinguish **design intent from validated results**. “Designed to reduce setup friction” is a defensible explanation of your decision. “Reduces setup friction” sounds like an outcome you measured. Small, honest observations are sufficient; you do not need to manufacture a formal research program around a personal project.

## Keep the AI story prominent—but edit its center of gravity

I would **not** advise downplaying AI because some hypothetical employer might dislike it. That would be poorly matched to your stated goal and to the public Mindbloom engineering brief.

I would, however, substantially shorten the sequence of agent-failure anecdotes on the main page.

The two strongest examples are the shader-validation mistake and the supposedly flaky timing test that exposed randomized preset durations. They demonstrate different and valuable forms of judgment: checking whether the evidence actually supports a conclusion, and investigating a failure instead of weakening the test. [GitHub](https://raw.githubusercontent.com/carterchadams/koggles/main/README.md)

Those deserve to stay.

The missed plan commit and staging-rule violation are less effective front-page examples. [GitHub](https://raw.githubusercontent.com/carterchadams/koggles/main/README.md) My concern is not that they are embarrassing. It is that they draw attention toward **managing agent behavior** rather than **delivering a good product**.

With all the incidents together, the story risks becoming:

> “I spent considerable effort keeping agents from doing the wrong thing.”

The story you want is:

> “I built a useful product quickly, and I established controls that caught plausible but incorrect work before I accepted it.”

That is a change in editorial emphasis, not a change in the truth.

I would keep a compact workflow explanation and two examples in the README, then link to a longer engineering case study. For each example, emphasize **the risk, the intervention, and the evidence that resolved it**.

One further distinction matters: a second AI model’s approval should be presented as a review mechanism, not the ultimate proof of correctness. Make the tests, measurements, and human acceptance decisions the endpoint of the story.

## Make the verification story easy to inspect—and internally consistent

There are two improvements here that matter more than cosmetic polish.

### Clarify the release-gate documentation

`RELEASING.md` says physical-phone checks, including rendering performance, must be completed and recorded before tagging a release. The README says goggles-mode frame timing remains unmeasured, while a v0.4.0 prerelease is available. [GitHub](https://raw.githubusercontent.com/carterchadams/koggles/main/RELEASING.md)

**That is not, by itself, proof that you released improperly.** A qualitative performance check and a frame-timing measurement are different things; the release gate may also cover a narrower scope than a reader assumes.

But the documents currently make the reviewer reconcile that distinction.

I would make it explicit: what was checked, on which device and mode, what remains unmeasured, and whether that remaining work is acceptable for the current prerelease. A documented, deliberate deferral is much more convincing than a strict gate whose relationship to the shipped artifact is unclear.

Do not solve this by removing the known limitation. Solve it by clarifying the acceptance criteria.

### Publish a small amount of primary evidence

`AGENTS.md` explicitly says that plans, review transcripts, and verification records are kept outside the repository, with the README serving as the public summary. [GitHub](https://raw.githubusercontent.com/carterchadams/koggles/main/AGENTS.md)

That is a reasonable repository-management decision. But it means the most distinctive part of your proof-of-work story is currently supported mainly by your own account of it.

I would not publish a mountain of agent transcripts. I would publish **one curated, inspectable example**:

> Original acceptance criterion → reviewer finding → changed implementation or measurement → observed result.

Link that example to the relevant commit and test. Clearly distinguish an original record from a retrospective explanation.

A short validation snapshot would also help: the version tested, device, automated checks performed, hands-on checks performed, and outstanding limitations. Keep CI results separate from physical-device results; your regular CI configuration already distinguishes the instrumented suite as a local gate. [GitHub](https://raw.githubusercontent.com/carterchadams/koggles/main/.github/workflows/ci.yml)

This would make the engineering story much easier to trust without requiring a reviewer to reverse-engineer the project.

## Package it as a product first, with an engineering case study underneath

The current README is trying to serve prospective users, maintainers, and hiring reviewers at once. I would give those readers a clearer route rather than simply adding more text.

My preferred sequence would be:

**Product promise and banner → actual demonstration → download and compatibility → your role and key decisions → compact engineering/AI case study → build, test, and licensing details.**

Lead with the experience, then credit the foundations. For example:

> **Immersive music visuals for Android—on your screen or in a phone headset.**

The projectM/MilkDrop lineage is useful and should remain visible, but it does not need to carry the burden of explaining why someone would use the application.

Three smaller presentation fixes are also worthwhile:

**Complete the repository’s About information.** GitHub currently shows no description, website, or topics. A clear description and a link to the demonstration or case study would make the repository feel more intentionally presented. [github.com](https://github.com/carterchadams/koggles)

**Give release notes a user-facing opening.** The current release pages provide a short change description followed by substantial checksum information. Retain the technical information, but begin with what improved, who should update, and any relevant limitation. [GitHub](https://github.com/carterchadams/koggles/releases)

**Use pull requests to show written judgment.** The recent PR I reviewed is titled “Dev” and has a one-line description. That is adequate for moving your own branches around, but it misses an opportunity to demonstrate how you communicate a change to another engineer. Future PRs could briefly explain the problem, the chosen solution, and the validation performed. [GitHub](https://github.com/carterchadams/koggles/pull/3)

These are small changes, but they reinforce the senior-engineer story through ordinary working artifacts rather than portfolio claims.

## How I would connect it to Mindbloom

I would not turn Koggles into a mock medical product or make the aesthetic look clinical merely to resemble the employer.

The stronger connection is that you can take an experiential idea, work through practical human constraints, use AI effectively, and remain accountable for the resulting software.

A brief explanation of your personal motivation could strengthen that connection, provided it is authentic and you are comfortable sharing it. Keep that separate from claims of therapeutic benefit. The project does not need to be presented as treatment, a validated therapeutic tool, or an official Mindbloom companion to be relevant.

Likewise, let your professional experience carry the evidence for production backend ownership and operating systems used by a business. Koggles can complement that by showing product initiative and AI-assisted delivery. I would not bolt on a backend or an LLM feature merely to match keywords in the role.

**My recommendation is to use this project, but revise the presentation before submitting.** The highest-value changes are an actual product demonstration, an explicit account of your ownership, and a compact case study connecting decisions to evidence.

Right now, I come away thinking, **“Carter has a thoughtful process for directing coding agents.”** The material is there to make the stronger impression: **“Carter can own a product, make sound engineering and design decisions, and use agents to deliver it.”**
