# BASE-04: protected print administration shell

## Implemented source, not yet runtime acceptance

The new /print/workbench route uses the existing normal login guard. It is not included in the anonymous whitelist or the preview-route exception. Static route names are retained when resetRouter clears dynamic routes. /, /index and default password-login destination now redirect to /print/workbench. Explicit same-site redirects retain query and hash; protocol-relative, external and backslash/control-character destinations fall back to the workbench.

src/print-admin contains an independent shell, workbench, context request/store, route definitions and the original 53-menu mapping. Workbench availability means the context page exists; it does not mean the full original P01 dashboard, all BASE-04 criteria, CORE-06 or any business page is accepted. P02-P53 remain disabled and have no business route. P47 stays available only in the independent visual-preview specimen when explicitly enabled; it is not exposed by the business menu.

The 60 px header, seven top-level entries (workbench plus six business modules), 168 px sidebar, 5 px gutter, 43 px tab bar/42 px selected tab, font stack and colors follow the established visual-baseline-shell.md and previously reviewed preview CSS. A workbench tab may be closed and reopened. The generic text brand is retained pending original asset handling. Full workbench-content visual evidence is incomplete; current contents are real identity/store information, not reconstructed business statistics. Screenshot pixel comparison and rendered browser acceptance remain pending.

## Real identity and failure handling

GET /admin-api/print/context is requested through the existing axios wrapper, preserving its Bearer token, tenant header and 401 token-refresh/relogin behavior. A narrow transformResponse preserves CommonResult business errors before the shared interceptor replaces them with a generic string. It never converts an error into successful data.

Codes agreed with the backend task: 1510000001 invalid/missing identity; 1510000002 missing tenant; 1510000003 permission/identity/tenant mismatch; 1510000004 unassigned store; 1510000005 ambiguous multiple store assignments; 1510000006 unsupported actor; 1510000007 missing/inactive store. The UI separately represents these groups, HTTP forbidden/session failures, connection failure and malformed service responses. It does not render them as an empty business list.

Successful context requires safe positive integer tenant/store/user IDs, non-empty store name and actorType STAFF. IDs larger than JavaScript's safe integer range are rejected rather than silently rounded. Context user ID must match the existing userStore identity before nickname/store information is shown. Context is cleared before requests, when leaving the shell and on logout; a request-version guard prevents a stale response restoring old context. No context or mock data is stored in browser storage. Logout uses the existing real server logout action. The password-login loading overlay now closes only when created, so validation or early request failure is not masked by an undefined-overlay exception.

## Validation still required

Static checks in evidence/base04/print-admin-static-checks.json verify all 53 original IDs/names/modules/groups, only P01 available, no /print whitelist addition, route preservation, default home routing, preview flag unchanged and whitespace. These checks do not prove compilation or authentication.

Primary agent must run the coordinated production build and browser checks: unauthenticated direct /print/workbench leads to login; successful login defaults there; explicit same-site redirect query/hash preserved; protected context loads the real store/user; absent store, forbidden, inactive store and offline errors remain distinguishable; no cached prior account after logout; top module/sidebar groups and tab close/reopen work; keyboard focus remains usable; compare the rendered 1280x720 shell to docs/visual-baseline images. No dependencies were installed, service restarted or production-site mutation performed by this task.
