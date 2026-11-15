// libultra_hands internal configuration hooks (not exported, not installed).
// SPDX-License-Identifier: BSL-1.0
#pragma once
#ifdef __cplusplus
extern "C" {
#endif
int ultra_hands_cfg_worker_threads(void);
int ultra_hands_cfg_ort_intra_op_threads(void);
int ultra_hands_cfg_ort_allow_spinning(void);
void ultra_hands_cfg_set_create_error(const char *msg);
#ifdef __cplusplus
}
#endif
