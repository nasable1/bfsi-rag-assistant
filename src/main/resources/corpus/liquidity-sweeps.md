# Demo Bank - Liquidity Management: Sweeps and Cash Concentration

_Illustrative policy for a fictional bank, written for this demo. Not regulatory guidance._

## Overview

Liquidity management lets a corporate group move balances between its accounts automatically, so surplus cash is concentrated where it earns the most and deficits are funded without overdraft charges.

## Zero balance accounts

In a zero balance structure, every participating sub-account is swept to the header account at the end of the day so that it closes at zero. Debit balances in sub-accounts are funded from the header account. Sub-accounts in a zero balance structure must belong to the same corporate group and be in the same currency.

## Target balance sweeps

A target balance sweep keeps a fixed amount in each sub-account and moves only the excess to the header account. The target is set per sub-account and can be changed by the customer with one checker approval; the new target applies from the next sweep cycle.

## Sweep timings

End-of-day sweeps run at 7:00 PM on working days. Intraday sweeps can be scheduled at 11:00 AM and 3:00 PM. A sweep that fails because of a debit freeze or a blocked account is retried once at the next cycle; if it fails again, the Relationship Manager is alerted.

## Interest

Credit balances in the header account earn interest at the rate agreed in the liquidity agreement. Notional pooling, where balances are offset for interest without moving funds, is not offered under this product.
