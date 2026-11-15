// View-settings entry point: the web apps' remembered display choices (web-apps-layout.md §3.6, L6),
// per browser (DEVICE scope, like native). Backed by `tenshu-sdk-js/.../ViewSettings.kt`.

import * as bundle from './_bundle'

export type DriveSortKey = 'name' | 'modified' | 'created' | 'size'
export type PhotosDensity = 'days' | 'months' | 'years'

export const DriveSettings = bundle.DriveSettings.getInstance()
export const PhotosSettings = bundle.PhotosSettings.getInstance()
