# Confirmed paper-pricing kernel

Status: source and 23 independent unit cases added on the user-owned server. Compilation and execution are pending the primary agent's build coordination. No API, database integration, payment settlement or original-site algorithm validation is claimed.

## Contract

- Java 17 pure domain classes under yudao-server/src/main/java/cn/iocoder/yudao/server/print/pricing; no Spring, database or external library dependencies.
- One document, an explicit selected-page count per copy, and a positive copy count. One page per printed side only. Page selection and page-count verification belong to file/API services.
- Simplex and duplex per-sheet prices are independently configured. Each duplex copy rounds its paper count up independently. 3 pages, 2 duplex copies, 0.20 CNY/sheet gives 4 sheets and 0.80 CNY.
- An optional single tier uses strictly-more-than total sheets for this document's copies. It replaces the price for every sheet. At 10 sheets the base price remains; at 11 sheets a threshold-10 tier of 0.15 CNY/sheet yields 1.65 CNY.
- The immutable result records input counts, physical sheets, price version, selected unit price, exact total and applied tier threshold.
- BigDecimal multiplication is exact. No implicit two-decimal rounding occurs. The test 3 sheets at 0.015 returns 0.045 as a calculation only, not an approved payment amount. Currency precision and settlement rules remain to be confirmed.
- Invalid/missing counts, mode, prices and version fail with IllegalArgumentException. Negative prices are rejected; explicit zero prices are retained. Sheet-count multiplication overflow is rejected. Duplex ceiling does not use pages+1, avoiding long overflow.

## Integration boundaries

The caller must obtain page count from validated files and pricing configuration from authorized server-side storage, never trust arbitrary client prices or page counts. Store/tenant permissions, concurrency, price-version persistence and transactional order snapshots belong to the subsequent service/API layer. This kernel does not persist data or authorize a business action.

Multiple tiers, across-file aggregation, N-up/booklet layouts, extras, binding/delivery, discount ordering, minimum charges, payment precision, refund allocation and free-quota policies remain unimplemented pending their rules. This is a source implementation of one CORE-02 subcomponent, still awaiting compilation and test execution; it does not complete CORE-02 or M2.

## Unit execution

The upstream yudao-server POM has no testing-framework dependency. The checks therefore use a dependency-free Java entry point, not automatic Maven/Surefire discovery. Run explicitly on the server after coordination with the main build:

```sh
cd /home/ubuntu/print-system
python3 src/backend/yudao-server/src/test/java/cn/iocoder/yudao/server/print/pricing/run-checks.py
```

The runner compiles only the five pricing classes and the checks, with Java 17 and bounded memory. Expected results are literal examples. It retains evidence/pricing/unit-checks.log and automatically deletes temporary compiled classes. A passing result proves the kernel's listed cases only; it is not a backend build, integration test, UI acceptance, real payment or physical-print test.
