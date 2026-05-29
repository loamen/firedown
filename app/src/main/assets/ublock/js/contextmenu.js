/*******************************************************************************

    uBlock Origin - a comprehensive, efficient content blocker
    Copyright (C) 2014-present Raymond Hill

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program.  If not, see {http://www.gnu.org/licenses/}.

    Home: https://github.com/gorhill/uBlock
*/

/******************************************************************************/

// Firedown: context menus are unused. The `menus` permission is not granted,
// so `vAPI.contextMenu` is always undefined — and uBlock's own factory here
// already collapsed to exactly this no-op `update()` in that case, leaving
// ~250 lines of menu-builder code parsed but never executed. Replaced with the
// equivalent stub (zero behaviour change).
//
// `update()` is the only member of this default export consumed anywhere
// (`contextMenu.update(...)` in start.js / tab.js / ublock.js / pagestore.js).
// The live `vAPI.contextMenu` methods (setEntries / onMustUpdate) are a
// separate object created in vapi-background.js and are untouched.

const contextMenu = {
    update: function() {}
};

export default contextMenu;
