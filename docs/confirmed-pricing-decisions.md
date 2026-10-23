# Confirmed pricing decisions

These decisions supplement the original taskbook; its historical research is not rewritten.

- Billing unit: paper sheets, with separate simplex and duplex prices. Each copy rounds duplex sheets upward independently.
- A single tier uses a strict greater-than threshold. Once matched, its unit price applies to all sheets in that group.
- User confirmed on 2026-10-23: aggregate each file separately, including all copies of that file. Do not combine different files, even when their print specifications match. Policy identifier: PER_FILE_ALL_COPIES.

Confirmed-rule examples using synthetic test prices only:

| Input | Expected |
| --- | --- |
| 3-page PDF, duplex, 2 copies, 0.20 per sheet | 4 sheets, 0.80 |
| 10 sheets, base 0.20, tier strictly over 10 at 0.15 | 2.00 |
| 11 sheets under the same schedule | 1.65 |
| Two distinct 6-page simplex files, one copy each | Each 1.20; combined total 2.40, no cross-file tier |
| One 6-page simplex file, two copies | 12 sheets at 0.15, 1.80 |

Still unconfirmed: multiple tier precedence, discounts and their stacking order, binding/delivery adjustments, minimum charges, settlement rounding and refund allocation. The demonstration price values above are not merchant prices.
