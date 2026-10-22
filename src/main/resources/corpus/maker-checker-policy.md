# Demo Bank - Maker-Checker Authorisation Policy

_Illustrative policy for a fictional bank, written for this demo. Not regulatory guidance._

## Principle

No single user may both create and approve a financial transaction. Every payment, beneficiary change and limit change needs a maker who captures it and a checker who approves it. The maker and the checker must be different users, and the system blocks a user from approving their own entry.

## Approval matrix

Payments up to Rs 25,00,000 need one checker. Payments above Rs 25,00,000 and up to Rs 5 crore need two checkers, at least one of whom holds the Senior Authoriser role. Payments above Rs 5 crore need two checkers plus a call-back confirmation to the customer's registered authorised signatory before release.

## Beneficiary and limit changes

Adding or modifying a beneficiary needs one checker. Increasing a user's transaction limit needs two checkers and takes effect only on the next business day. Decreasing a limit takes effect immediately after one checker approves it.

## Time limits

An entry not approved within 24 hours expires and must be captured again by the maker. Expired entries are kept in the audit log with the status EXPIRED.

## Overrides

An override of the approval matrix is permitted only in a declared business-continuity event, must be approved by the Head of Operations, and must be reported to the Operational Risk team within one business day.
