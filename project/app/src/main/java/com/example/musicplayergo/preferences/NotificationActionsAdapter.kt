package com.example.musicplayergo.preferences

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import com.example.musicplayergo.GoConstants
import com.example.musicplayergo.GoPreferences
import com.example.musicplayergo.databinding.NotificationActionsItemBinding
import com.example.musicplayergo.models.NotificationAction
import com.example.musicplayergo.utils.Theming


class NotificationActionsAdapter: RecyclerView.Adapter<NotificationActionsAdapter.CheckableItemsHolder>() {

    var selectedActions = GoPreferences.getPrefsInstance().notificationActions

    private val mActions = listOf(
        NotificationAction(GoConstants.REPEAT_ACTION, GoConstants.CLOSE_ACTION), // default
        NotificationAction(GoConstants.REWIND_ACTION, GoConstants.FAST_FORWARD_ACTION),
        NotificationAction(GoConstants.FAVORITE_ACTION, GoConstants.CLOSE_ACTION),
        NotificationAction(GoConstants.FAVORITE_POSITION_ACTION, GoConstants.CLOSE_ACTION)
    )

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CheckableItemsHolder {
        val binding = NotificationActionsItemBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return CheckableItemsHolder(binding)
    }

    override fun getItemCount() = mActions.size

    override fun onBindViewHolder(holder: CheckableItemsHolder, position: Int) {
        holder.bindItems()
    }

    inner class CheckableItemsHolder(private val binding: NotificationActionsItemBinding): RecyclerView.ViewHolder(binding.root) {

        fun bindItems() {

            with(binding) {

                val context = binding.root.context

                notifAction0.setImageResource(
                    Theming.getNotificationActionIcon(mActions[absoluteAdapterPosition].first, isNotification = false)
                )
                notifAction1.setImageResource(
                    Theming.getNotificationActionIcon(mActions[absoluteAdapterPosition].second, isNotification = false)
                )
                radio.isChecked = selectedActions == mActions[absoluteAdapterPosition]

                root.contentDescription = context.getString(Theming.getNotificationActionTitle(mActions[absoluteAdapterPosition].first))

                root.setOnClickListener {
                    notifyItemChanged(mActions.indexOf(selectedActions))
                    selectedActions = mActions[absoluteAdapterPosition]
                    notifyItemChanged(absoluteAdapterPosition)
                    GoPreferences.getPrefsInstance().notificationActions = selectedActions
                }

                root.setOnLongClickListener {
                    Toast.makeText(
                        context,
                        Theming.getNotificationActionTitle(mActions[absoluteAdapterPosition].first),
                        Toast.LENGTH_SHORT
                    ).show()
                    return@setOnLongClickListener true
                }
            }
        }
    }
}
