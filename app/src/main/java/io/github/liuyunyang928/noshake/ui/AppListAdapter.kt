package io.github.liuyunyang928.noshake.ui

import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import io.github.liuyunyang928.noshake.databinding.ItemAppBinding

/** 列表行数据：应用信息 + 当前是否已启用拦截。 */
data class AppEntry(
    val packageName: String,
    val label: String,
    val icon: Drawable,
    val blocked: Boolean,
)

/** DiffUtil 回调：按包名识别是否同一个应用，按名称与开关状态判断内容是否变化。 */
private val APP_DIFF_CALLBACK = object : DiffUtil.ItemCallback<AppEntry>() {
    override fun areItemsTheSame(oldItem: AppEntry, newItem: AppEntry): Boolean =
        oldItem.packageName == newItem.packageName

    override fun areContentsTheSame(oldItem: AppEntry, newItem: AppEntry): Boolean =
        oldItem.label == newItem.label && oldItem.blocked == newItem.blocked
}

/**
 * 应用列表适配器。
 *
 * 行内开关与整行点击都只回调 [onToggle]，真正的状态读写由 Activity 通过 ConfigStore 完成，
 * 再重新下发列表；适配器自身不持有配置，避免出现第二份状态来源。
 */
class AppListAdapter(
    private val onToggle: (AppEntry) -> Unit,
) : ListAdapter<AppEntry, AppListAdapter.AppViewHolder>(APP_DIFF_CALLBACK) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppViewHolder {
        val binding = ItemAppBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return AppViewHolder(binding, onToggle)
    }

    override fun onBindViewHolder(holder: AppViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class AppViewHolder(
        private val binding: ItemAppBinding,
        private val onToggle: (AppEntry) -> Unit,
    ) : RecyclerView.ViewHolder(binding.root) {

        /** 当前行绑定的数据，点击事件以它为准，避免复用后操作到上一行的应用。 */
        private var currentEntry: AppEntry? = null

        init {
            // 整行点击与开关点击行为一致：切换拦截状态。
            binding.root.setOnClickListener {
                currentEntry?.let(onToggle)
            }
        }

        fun bind(entry: AppEntry) {
            currentEntry = entry
            binding.appIcon.setImageDrawable(entry.icon)
            binding.appName.text = entry.label
            binding.appPackage.text = entry.packageName

            // 复用 ViewHolder 时必须先移除旧监听，否则 setChecked 会回调到上一行的应用。
            binding.appSwitch.setOnCheckedChangeListener(null)
            binding.appSwitch.isChecked = entry.blocked
            binding.appSwitch.setOnCheckedChangeListener { _, _ ->
                // 状态以 Activity 里的 ConfigStore 为准，这里不比较（可能已过期的）
                // entry.blocked，否则快速连点会吞掉一次切换并造成开关与配置短暂不同步。
                currentEntry?.let(onToggle)
            }
        }
    }
}
