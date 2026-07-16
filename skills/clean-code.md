---
name: clean-code
description: Make any AI coding assistant write better-quality code, using Robert C. Martin's Clean Code principles as a cross-cutting layer, not a standalone persona — built to stack with other skills.md files (framework, document-generation, design-system, or custom) instead of competing with them. Use whenever writing, generating, refactoring, or reviewing code in any language — naming, function size, comments, formatting, error handling, tests — even if the user didn't mention style, and even when another skill already governs the domain-specific part of the task. Applies to Android/Kotlin, embedded/IoT, Python, data pipelines, web and backend code, or any other context. Covers meaningful naming, small single-purpose functions, minimal comments, consistent formatting, objects-vs-data-structures separation, exception-based error handling, and F.I.R.S.T. test hygiene — deferring to another skill's explicit instructions or genuine framework/performance constraints only when they truly conflict.
---

# Clean Code

A quality-review layer, not a standalone persona — built to combine with whatever other skills are active, not replace them. Whatever the primary task is — building an Android screen, writing firmware for a microcontroller, assembling a document, wrangling a data pipeline — this skill runs underneath it, shaping how the resulting code is named, sized, and structured, while any domain-specific skill keeps making the domain-specific calls. The aim, drawn from Robert C. Martin's *Clean Code* craftsmanship principles, is code that reads clearly to the next person (often a future version of the same author) without needing a guided tour.

## Why this is worth doing

Code gets read far more often than it gets written. A function whose name and shape make its purpose obvious saves every future reader — including an AI revisiting it later — the work of reverse-engineering intent. Treat what follows as an investment that pays off the second time anyone touches the code, not busywork for its own sake.

## Core principles

### 1. Meaningful names
A good variable, function, or class name should let a reader infer its purpose and correct usage on its own, without hunting through the rest of the file or leaning on a comment to fill the gap. Prefer a name a domain expert would recognize over a generic label.

- Skip low-information filler like `Data`, `Info`, `Manager`, or `Helper` tacked onto a name unless it genuinely adds meaning.
- A name should earn its length: `daysSinceLastLogin` beats both `d` and an unreadably long sentence-as-identifier.
- Booleans read as questions or assertions: `isActive`, `hasPermission`.

```
// Before
int d; // elapsed time in days

// After
int elapsedTimeInDays;
```

### 2. Small, single-purpose functions
Keep functions short enough to do one thing, at one level of abstraction. If describing what a function does requires the word "and," it's probably two functions wearing a trenchcoat.

- Aim low on parameter count — zero or one is easiest to reason about; three or more is usually a sign the parameters want to be an object.
- A function should either change state or answer a question, not both — a `getX()` that quietly mutates something will surprise its caller.
- When a function does several things, extract each into its own well-named function and call them from a short orchestrator.

```
// Before: validation, pricing, and notification all inline
function processOrder(order) {
  if (!order.items.length) throw new Error("empty order");
  let total = 0;
  for (const item of order.items) total += item.price * item.qty;
  if (order.coupon) total *= 0.9;
  emailClient.send(order.customerEmail, `Total: ${total}`);
}

// After: same behavior, each responsibility named and isolated
function processOrder(order) {
  validateOrder(order);
  const total = calculateTotal(order);
  sendConfirmation(order, total);
}
```

### 3. Comments as a last resort
Well-named code explains itself; reach for a comment only when the code genuinely can't say something on its own — a legal notice, a warning about a non-obvious consequence, or the "why" behind a decision that looks wrong out of context. A comment that just restates the next line, or apologizes for confusing code, is a sign the code needs rewriting rather than annotating.

### 4. Formatting & structure
Vertical layout is a readability tool: keep tightly related lines close together and add a blank line where a new idea starts. Declare a variable as close as possible to where it's first used rather than hoisting everything to the top. Order functions so a caller appears above the functions it calls (the "stepdown rule") — the file should read top-to-bottom, headline first, detail after.

### 5. Objects vs. data structures
These are two different tools, and blending them tends to produce the worst of both:

- **Objects** hide their internal representation and expose behavior. Resist adding a getter/setter for every field by reflex — if outside code needs to reach in and manipulate an object's internals, that behavior probably belongs inside the object instead.
- **Data structures** (DTOs, records, structs) do the opposite: expose data plainly, with no meaningful behavior attached.
- Follow the Law of Demeter for objects: a method should talk to its own fields, its parameters, and things it directly owns or creates — not reach through one object to grab another and call a method on that (`a.getB().getC().doSomething()` is the classic warning sign).

### 6. Error handling
Prefer exceptions over error codes. Return codes force every caller to remember to check them, cluttering the normal path with error-checking noise; exceptions keep the happy path readable and let failures surface where they're actually handled. When a function's real job gets buried inside a `try/catch`, pull the guarded logic into its own function — one function does the work, another handles what happens when it fails.

### 7. Clean tests — F.I.R.S.T.
Tests are code too, and they decay fastest when neglected. Keep them:
- **Fast** — slow tests stop getting run.
- **Independent** — no test should depend on another running first.
- **Repeatable** — same result in any environment, not just "works on my machine."
- **Self-validating** — pass or fail as a boolean, not "go check the log."
- **Timely** — written close to the production code, not bolted on long after.

Favor a small number of tightly-focused assertions per test over one giant test checking unrelated things — a failure's test name and assertions should say what broke without forcing anyone to step through the code.

## Working alongside other skills and real constraints

This skill is a cross-cutting layer, designed to stack with other skills.md files rather than compete with them. When a task also touches a domain-specific skill — a frontend-design skill choosing layout and visual tokens, a docx/pptx/xlsx skill assembling a document, a platform skill covering Android or embedded conventions — let that skill own its domain decisions, and use this one to keep whatever code gets written along the way well-named, appropriately sized, and cleanly structured. One decides *what* the code should do and *which* APIs, templates, or patterns to use; this one shapes *how* it's written. If the two genuinely conflict, defer to the more specific skill's explicit instruction, and apply whatever clean-code judgment still fits inside it — a clear name, a focused function body — rather than overriding it outright.

The same logic extends to hard technical constraints. A memory-constrained microcontroller that can't absorb the overhead of extra small functions, a real-time deadline where an exception's stack unwind is too slow, or an idiomatic pattern a language or framework expects: let stability and performance win, but "winning" means finding the cleanest structure the constraint still allows, not abandoning the principle entirely. A flatter function forced by an interrupt handler can still have a clear name and a single job.

And don't let "be clean" become license to invent structure nobody asked for. A quick script that's genuinely small and clear can stay one well-named function — it doesn't need five files and three layers of abstraction just to count as clean.

## How to apply this

Use this as a lens applied after solving the real problem, not a checklist recited out loud:

1. Solve the primary task that was actually asked for.
2. Pass the result through the principles above — naming, function size, duplication, error handling, formatting.
3. Clean up formatting and hand back working code.

Apply it quietly. There's no need to narrate "I extracted this into a function for single responsibility" on every answer — just return code that already reflects it. Speak up only when a genuine tradeoff is being made, e.g. flagging that a return code was kept instead of an exception because the target platform has exceptions disabled.
