// libultra_hands: places where a hand begun by a tile was dropped within a few frames: no tile's find is believed there for a moment.
// SPDX-License-Identifier: BSL-1.0
#pragma once
#include <mutex>

namespace ultra_phantom {

struct Spot
{
	float x = 0, y = 0;
	int until = -1;
};

inline Spot spots[2][8];
inline int next_slot[2] = {0, 0};
inline std::mutex lock;

inline void add(int view, float x, float y, int until)
{
	std::lock_guard<std::mutex> g(lock);
	Spot &s = spots[view & 1][next_slot[view & 1]++ & 7];
	s.x = x;
	s.y = y;
	s.until = until;
}

inline bool near(int view, float px, float py, int counter, float radius)
{
	std::lock_guard<std::mutex> g(lock);
	for (const Spot &s : spots[view & 1]) {
		if (counter < s.until && (px - s.x) * (px - s.x) + (py - s.y) * (py - s.y) < radius * radius) {
			return true;
		}
	}
	return false;
}

} // namespace ultra_phantom
