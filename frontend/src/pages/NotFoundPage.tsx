import { Anchor, Stack } from '@mantine/core'
import { Link } from 'react-router'
import { PageHeader } from '../components/states'

export function NotFoundPage() {
  return (
    <Stack>
      <PageHeader title="Page not found" description="This page does not exist, or the link is out of date." />
      <Anchor component={Link} to="/">
        Go to the home page
      </Anchor>
    </Stack>
  )
}
