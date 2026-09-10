package com.zenith.thermal

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.TextView

class AppAdapter(context: Context) : BaseAdapter() {
    private val inflater = LayoutInflater.from(context)
    private val all = ArrayList<AppItem>()
    private val shown = ArrayList<AppItem>()

    fun setItems(items: List<AppItem>) {
        all.clear()
        all.addAll(items)
        filter("")
    }

    fun filter(query: String?) {
        shown.clear()
        val q = query?.trim()?.lowercase() ?: ""
        for (item in all) {
            if (q.isEmpty() || item.name.lowercase().contains(q) || item.pkg.lowercase().contains(q)) {
                shown.add(item)
            }
        }
        notifyDataSetChanged()
    }

    override fun getCount(): Int = shown.size
    override fun getItem(position: Int): AppItem = shown[position]
    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: inflater.inflate(R.layout.app_row, parent, false)
        val item = shown[position]
        view.findViewById<ImageView>(R.id.icon).setImageDrawable(item.icon)
        view.findViewById<TextView>(R.id.name).text = item.name
        view.findViewById<TextView>(R.id.packageName).text = item.pkg
        val badge = view.findViewById<TextView>(R.id.profile)
        if (item.profileId >= 0) {
            badge.text = Profile.name(item.profileId)
            badge.visibility = View.VISIBLE
            badge.backgroundTintList = android.content.res.ColorStateList.valueOf(
                if (item.profileId == 0) android.graphics.Color.rgb(58, 74, 90)
                else android.graphics.Color.rgb(92, 167, 255)
            )
        } else {
            badge.visibility = View.GONE
        }
        return view
    }
}
